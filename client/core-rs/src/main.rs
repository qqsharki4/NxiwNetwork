use anyhow::{bail, Context, Result};
use async_trait::async_trait;
use base64::Engine;
use bytes::Bytes;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::env;
use std::io::Write;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, ToSocketAddrs};
use std::process;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::net::UdpSocket;
use tokio::sync::{broadcast, mpsc, watch, Mutex};
use tokio::task::JoinHandle;
use tokio::time::{self, MissedTickBehavior};
use turn::client::{Client, ClientConfig};
use util::Conn;
use webrtc_dtls::cipher_suite::CipherSuiteId;
use webrtc_dtls::config::{Config, ExtendedMasterSecretType};
use webrtc_dtls::conn::DTLSConn;
use webrtc_dtls::crypto::Certificate;

mod tcp_turn_conn;

use tcp_turn_conn::TcpTurnConn;

const DEFAULT_VK_APP_ID: &str = "6287487";
const DEFAULT_VK_APP_SECRET: &str = "QbYic1K3lEV5kTGiqlq2";
const OK_APP_KEY: &str = "CGMMEJLGDIHBABABA";
const WORKER_SEND_BUF: usize = 128;
const WORKERS_PER_GROUP: usize = 12;
const MAX_CREDS_RETRIES: usize = 5;
const RETURN_BUF: usize = 384;
const READ_BUF_SIZE: usize = 2000;
const WAKEUP_PACKET: &[u8] = b"WAKEUP";
const DEFAULT_CYCLE_SECS: u64 = 36_000;
const GROUP_READY_DELAY: Duration = Duration::from_secs(2);

type Packet = Bytes;

unsafe extern "C" {
    fn nxiw_backend_contract_version() -> u32;
    fn nxiw_fast_checksum(data: *const u8, len: usize) -> u32;
}

#[derive(Clone)]
struct CoreArgs {
    peer: String,
    hashes: Vec<String>,
    secondary_hash: Option<String>,
    workers: usize,
    listen: String,
    use_udp: bool,
    split_tunnel: bool,
    no_dns: bool,
    sni: String,
    device_id: String,
    password: String,
    user_agent: String,
    captcha_mode: String,
    keepalive: Duration,
    turn_host: Option<String>,
    turn_port: Option<String>,
    vk_app_id: String,
    vk_app_secret: String,
}

struct Credentials {
    user: String,
    pass: String,
    turn_urls: Vec<String>,
    lifetime_seconds: i64,
}

struct TurnParams {
    host: Option<String>,
    port: Option<String>,
    sni: String,
}

#[derive(Default)]
struct Stats {
    active_connections: AtomicI32,
    reconnects: AtomicI64,
    packets_up: AtomicI64,
    packets_down: AtomicI64,
    total_bytes_up: AtomicI64,
    total_bytes_down: AtomicI64,
    dropped_packets: AtomicI64,
    creds_errors: AtomicI64,
}

#[derive(Clone)]
struct WorkerSlot {
    id: usize,
    tx: mpsc::Sender<Packet>,
    queued: usize,
}

struct Dispatcher {
    socket: Arc<UdpSocket>,
    client_addr: StdMutex<Option<SocketAddr>>,
    workers: StdMutex<Vec<WorkerSlot>>,
    rr_index: AtomicUsize,
    return_tx: mpsc::Sender<Packet>,
    stats: Arc<Stats>,
}

struct PeerConn {
    relay: Arc<dyn Conn + Send + Sync>,
    peer: SocketAddr,
}

#[derive(Clone, Copy, Debug)]
enum WorkerEventKind {
    Quota,
    CredsDead,
    StreamClosed,
}

#[derive(Clone, Copy, Debug)]
struct WorkerEvent {
    worker_id: usize,
    kind: WorkerEventKind,
}

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("Ошибка: {err:#}");
        process::exit(1);
    }
}

async fn run() -> Result<()> {
    let raw_args: Vec<String> = env::args().skip(1).collect();
    let args = parse_core_args(&raw_args)?;
    let contract_version = unsafe { nxiw_backend_contract_version() };
    let args_blob = raw_args.join("\0");
    let args_checksum = unsafe { nxiw_fast_checksum(args_blob.as_ptr(), args_blob.len()) };

    println!(
        "[ЯДРО] Rust backend, contract v{}, args={:08x}",
        contract_version, args_checksum
    );
    println!(
        "[ЯДРО] Экспериментально: UDP/TCP TURN + DTLS data path. RJS Classic включён, slider fallback через WV."
    );

    let peer = resolve_socket_addr(&args.peer).context("разбор пира")?;
    let local_socket = Arc::new(
        UdpSocket::bind(&args.listen)
            .await
            .with_context(|| format!("слушатель {}", args.listen))?,
    );
    let local_port = local_socket
        .local_addr()
        .map(|addr| addr.port().to_string())
        .unwrap_or_else(|_| "9000".to_string());

    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let (pause_tx, pause_rx) = watch::channel(false);
    let (captcha_tx, _) = broadcast::channel(8);
    tokio::spawn(stdin_loop(
        captcha_tx.clone(),
        shutdown_tx.clone(),
        pause_tx.clone(),
    ));

    let stats = Arc::new(Stats::default());
    tokio::spawn(stats_loop(stats.clone(), shutdown_rx.clone()));

    println!("[КЛИЕНТ] ═══════════════════════════════════════");
    println!("[КЛИЕНТ] VK App: {}", args.vk_app_id);
    println!("[КЛИЕНТ] Воркеров: {}", args.workers);
    println!("[КЛИЕНТ] Хешей: {}", args.hashes.len());
    println!("[КЛИЕНТ] Слушаю: {} | Пир: {}", args.listen, args.peer);
    println!(
        "[КЛИЕНТ] Протокол: {}",
        if args.use_udp { "UDP" } else { "TCP" }
    );
    println!("[КЛИЕНТ] Device ID: {}", args.device_id);
    println!("[КЛИЕНТ] Обход капчи: {}", args.captcha_mode);
    println!("[КЛИЕНТ] Keepalive: {} сек", args.keepalive.as_secs());
    if args.split_tunnel {
        println!("[КЛИЕНТ] Split tunnel: включён");
    }
    if args.no_dns {
        println!("[КЛИЕНТ] No DNS flag: включён");
    }
    println!("[КЛИЕНТ] ═══════════════════════════════════════");

    let dispatcher = Dispatcher::start(local_socket, stats.clone(), shutdown_rx.clone());
    let (config_tx, mut config_rx) = mpsc::channel::<String>(1);
    let config_sent = Arc::new(AtomicBool::new(false));

    tokio::spawn({
        let shutdown_rx = shutdown_rx.clone();
        let peer_ip = peer.ip();
        let split_tunnel = args.split_tunnel;
        let no_dns = args.no_dns;
        async move {
            print_first_config(&mut config_rx, shutdown_rx, peer_ip, split_tunnel, no_dns).await;
        }
    });

    let params = Arc::new(TurnParams {
        host: args.turn_host.clone(),
        port: args.turn_port.clone(),
        sni: args.sni.clone(),
    });
    let auth_gate = Arc::new(Mutex::new(()));
    let group_count = args.workers.div_ceil(WORKERS_PER_GROUP);
    let mut handles = Vec::with_capacity(group_count);
    let mut next_worker_id = 1usize;
    let mut previous_ready_rx: Option<watch::Receiver<bool>> = None;

    for group_index in 0..group_count {
        let group_id = group_index + 1;
        let remaining = args.workers - group_index * WORKERS_PER_GROUP;
        let group_size = remaining.min(WORKERS_PER_GROUP);
        let mut worker_ids = Vec::with_capacity(group_size);
        for _ in 0..group_size {
            worker_ids.push(next_worker_id);
            next_worker_id += 1;
        }

        let (ready_tx, ready_rx) = watch::channel(false);
        let signal_ready = if group_index + 1 < group_count {
            Some(ready_tx)
        } else {
            None
        };
        let wait_ready = previous_ready_rx.take();
        previous_ready_rx = Some(ready_rx);

        handles.push(tokio::spawn(group_loop(
            group_id,
            group_index,
            worker_ids,
            group_index == 0,
            peer,
            local_port.clone(),
            args.clone(),
            params.clone(),
            dispatcher.clone(),
            config_tx.clone(),
            config_sent.clone(),
            stats.clone(),
            captcha_tx.clone(),
            auth_gate.clone(),
            shutdown_rx.clone(),
            pause_rx.clone(),
            wait_ready,
            signal_ready,
        )));
    }
    drop(config_tx);

    wait_for_shutdown(shutdown_rx).await;
    for handle in handles {
        let _ = handle.await;
    }
    println!("[КЛИЕНТ] Все воркеры завершены");
    Ok(())
}

fn parse_core_args(args: &[String]) -> Result<CoreArgs> {
    let mut peer = String::new();
    let mut vk_raw = String::new();
    let mut secondary_hash = None;
    let mut workers = 24usize;
    let mut listen = "127.0.0.1:9000".to_string();
    let mut use_udp = false;
    let mut use_tcp = false;
    let mut split_tunnel = false;
    let mut no_dns = false;
    let mut sni = String::new();
    let mut device_id = "unknown".to_string();
    let mut password = String::new();
    let mut user_agent = "Mozilla/5.0".to_string();
    let mut captcha_mode = "rjs".to_string();
    let mut keepalive_seconds = 10u64;
    let mut turn_host = None;
    let mut turn_port = None;
    let mut vk_app_id = DEFAULT_VK_APP_ID.to_string();
    let mut vk_app_secret = DEFAULT_VK_APP_SECRET.to_string();

    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "-peer" => peer = next_value(args, &mut index),
            "-vk" => vk_raw = next_value(args, &mut index),
            "-vk2" => secondary_hash = Some(next_value(args, &mut index)),
            "-n" => workers = next_value(args, &mut index).parse().unwrap_or(workers),
            "-listen" => listen = next_value(args, &mut index),
            "-sni" => sni = next_value(args, &mut index),
            "-device-id" => device_id = next_value(args, &mut index),
            "-password" => password = next_value(args, &mut index),
            "-user-agent" => user_agent = next_value(args, &mut index),
            "-captcha-mode" => captcha_mode = next_value(args, &mut index),
            "-keepalive-sec" => {
                keepalive_seconds = next_value(args, &mut index)
                    .parse()
                    .unwrap_or(keepalive_seconds)
            }
            "-turn" => turn_host = Some(next_value(args, &mut index)),
            "-port" => turn_port = Some(next_value(args, &mut index)),
            "-vk-app-id" => vk_app_id = next_value(args, &mut index),
            "-vk-app-secret" => vk_app_secret = next_value(args, &mut index),
            "-udp" => use_udp = true,
            "-tcp" => use_tcp = true,
            "-split" => split_tunnel = true,
            "-nodns" => no_dns = true,
            _ => {}
        }
        index += 1;
    }

    if peer.is_empty() {
        bail!("[КЛИЕНТ] Нужен -peer");
    }
    let hashes = parse_hashes(&vk_raw);
    if hashes.is_empty() {
        bail!("[КЛИЕНТ] Нет хешей VK");
    }
    if !use_tcp && !use_udp {
        use_tcp = true;
    }
    workers = workers.clamp(1, 72);
    keepalive_seconds = keepalive_seconds.clamp(5, 60);

    Ok(CoreArgs {
        peer,
        hashes,
        secondary_hash: secondary_hash.and_then(|raw| parse_hashes(&raw).into_iter().next()),
        workers,
        listen,
        use_udp: use_udp || !use_tcp,
        split_tunnel,
        no_dns,
        sni,
        device_id,
        password,
        user_agent,
        captcha_mode,
        keepalive: Duration::from_secs(keepalive_seconds),
        turn_host: turn_host.filter(|v| !v.trim().is_empty()),
        turn_port: turn_port.filter(|v| !v.trim().is_empty()),
        vk_app_id,
        vk_app_secret,
    })
}

fn next_value(args: &[String], index: &mut usize) -> String {
    if *index + 1 >= args.len() {
        return String::new();
    }
    *index += 1;
    args[*index].clone()
}

fn parse_hashes(raw: &str) -> Vec<String> {
    let mut hashes = Vec::new();
    for chunk in raw.split([',', '\n', '\r']) {
        let linked = extract_vk_join_hashes(chunk);
        if !linked.is_empty() {
            hashes.extend(linked);
            continue;
        }
        for token in chunk.split_whitespace() {
            let normalized = normalize_vk_hash(token);
            if !normalized.is_empty() {
                hashes.push(normalized);
            }
        }
    }
    hashes
}

fn extract_vk_join_hashes(raw: &str) -> Vec<String> {
    let mut hashes = Vec::new();
    let pattern = "/call/join/";
    let mut offset = 0usize;
    while let Some(relative) = raw[offset..].find(pattern) {
        let start = offset + relative + pattern.len();
        let rest = &raw[start..];
        let end = rest
            .find(|ch: char| ch.is_whitespace() || matches!(ch, '/' | '?' | '#' | ',' | ';'))
            .unwrap_or(rest.len());
        let normalized = normalize_vk_hash(&rest[..end]);
        if !normalized.is_empty() {
            hashes.push(normalized);
        }
        offset = start + end;
    }
    hashes
}

fn normalize_vk_hash(raw: &str) -> String {
    let mut value = raw.trim().trim_matches([',', ';']);
    if let Some(index) = value.find("/call/join/") {
        value = &value[index + "/call/join/".len()..];
    }
    let cut = value.find(['/', '?', '#', ',', ';']).unwrap_or(value.len());
    value[..cut].trim().trim_end_matches('/').to_string()
}

async fn stdin_loop(
    captcha_tx: broadcast::Sender<String>,
    shutdown_tx: watch::Sender<bool>,
    pause_tx: watch::Sender<bool>,
) {
    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        let trimmed = line.trim().to_string();
        if !trimmed.contains("error:tunnel stopped") {
            println!("[STDIN] {trimmed}");
        }
        if trimmed == "STOP" {
            let _ = shutdown_tx.send(true);
            return;
        }
        if trimmed == "PAUSE" {
            let _ = pause_tx.send(true);
            continue;
        }
        if trimmed == "RESUME" {
            let _ = pause_tx.send(false);
            continue;
        }
        if let Some(result) = trimmed.strip_prefix("CAPTCHA_RESULT|") {
            let _ = captcha_tx.send(result.to_string());
        }
    }
}

async fn stats_loop(stats: Arc<Stats>, mut shutdown_rx: watch::Receiver<bool>) {
    let mut ticker = time::interval(Duration::from_secs(1));
    ticker.set_missed_tick_behavior(MissedTickBehavior::Delay);
    let mut last_time = std::time::Instant::now();
    let mut last_up = 0i64;
    let mut last_down = 0i64;
    let mut last_packets_up = 0i64;
    let mut last_packets_down = 0i64;
    let mut ticks = 0u64;
    loop {
        tokio::select! {
            _ = shutdown_rx.changed() => {
                if *shutdown_rx.borrow() {
                    return;
                }
            }
            _ = ticker.tick() => {
                let active = stats.active_connections.load(Ordering::Relaxed);
                let packets_up = stats.packets_up.load(Ordering::Relaxed);
                let packets_down = stats.packets_down.load(Ordering::Relaxed);
                let up = stats.total_bytes_up.load(Ordering::Relaxed);
                let down = stats.total_bytes_down.load(Ordering::Relaxed);
                let dropped = stats.dropped_packets.load(Ordering::Relaxed);
                let now = std::time::Instant::now();
                let elapsed_seconds = now.duration_since(last_time).as_secs_f64().max(1.0);
                let up_bps = (((up - last_up) as f64) / elapsed_seconds).max(0.0) as i64;
                let down_bps = (((down - last_down) as f64) / elapsed_seconds).max(0.0) as i64;
                let up_pps = (((packets_up - last_packets_up) as f64) / elapsed_seconds).max(0.0) as i64;
                let down_pps = (((packets_down - last_packets_down) as f64) / elapsed_seconds).max(0.0) as i64;
                println!("[CORE_METRICS] active={active} total_up={up} total_down={down} up_bps={up_bps} down_bps={down_bps} packets_up={packets_up} packets_down={packets_down} up_pps={up_pps} down_pps={down_pps} drops={dropped}");

                let total_mb = (up + down) as f64 / (1024.0 * 1024.0);
                let up_mb = up as f64 / (1024.0 * 1024.0);
                let down_mb = down as f64 / (1024.0 * 1024.0);
                ticks += 1;
                if ticks % 3 == 0 {
                    println!("[СТАТИСТИКА] Активных: {active} | Всего: {total_mb:.2} МБ | ↑ {up_mb:.2} МБ / {packets_up} пак | ↓ {down_mb:.2} МБ / {packets_down} пак | Дропы: {dropped}");
                }

                last_time = now;
                last_up = up;
                last_down = down;
                last_packets_up = packets_up;
                last_packets_down = packets_down;
            }
        }
    }
}

async fn wait_for_shutdown(mut shutdown_rx: watch::Receiver<bool>) {
    loop {
        if *shutdown_rx.borrow() {
            return;
        }
        if shutdown_rx.changed().await.is_err() {
            return;
        }
    }
}

impl Dispatcher {
    fn start(
        socket: Arc<UdpSocket>,
        stats: Arc<Stats>,
        shutdown_rx: watch::Receiver<bool>,
    ) -> Arc<Self> {
        let (return_tx, return_rx) = mpsc::channel(RETURN_BUF);
        let dispatcher = Arc::new(Self {
            socket,
            client_addr: StdMutex::new(None),
            workers: StdMutex::new(Vec::new()),
            rr_index: AtomicUsize::new(0),
            return_tx,
            stats,
        });
        tokio::spawn(dispatcher.clone().read_loop(shutdown_rx.clone()));
        tokio::spawn(dispatcher.clone().write_loop(return_rx, shutdown_rx));
        dispatcher
    }

    fn register(&self, worker_id: usize) -> mpsc::Receiver<Packet> {
        let (tx, rx) = mpsc::channel(WORKER_SEND_BUF);
        let mut workers = match self.workers.lock() {
            Ok(workers) => workers,
            Err(_) => return rx,
        };
        workers.push(WorkerSlot {
            id: worker_id,
            tx,
            queued: 0,
        });
        println!(
            "[ДИСП] Воркер #{worker_id} зарегистрирован (всего: {})",
            workers.len()
        );
        rx
    }

    fn unregister(&self, worker_id: usize) {
        let mut workers = match self.workers.lock() {
            Ok(workers) => workers,
            Err(_) => return,
        };
        workers.retain(|worker| worker.id != worker_id);
        println!(
            "[ДИСП] Воркер #{worker_id} отключён (осталось: {})",
            workers.len()
        );
    }

    async fn read_loop(self: Arc<Self>, mut shutdown_rx: watch::Receiver<bool>) {
        let mut buf = vec![0u8; READ_BUF_SIZE];
        loop {
            tokio::select! {
                _ = shutdown_rx.changed() => {
                    if *shutdown_rx.borrow() {
                        return;
                    }
                }
                result = self.socket.recv_from(&mut buf) => {
                    let (n, addr) = match result {
                        Ok(value) => value,
                        Err(_) => continue,
                    };
                    if let Ok(mut client_addr) = self.client_addr.lock() {
                        *client_addr = Some(addr);
                    }
                    self.stats.total_bytes_up.fetch_add(n as i64, Ordering::Relaxed);
                    self.dispatch_packet(Bytes::copy_from_slice(&buf[..n]));
                }
            }
        }
    }

    fn dispatch_packet(&self, packet: Packet) {
        let mut workers = match self.workers.lock() {
            Ok(workers) => workers,
            Err(_) => {
                self.stats.dropped_packets.fetch_add(1, Ordering::Relaxed);
                return;
            }
        };
        if workers.is_empty() {
            self.stats.dropped_packets.fetch_add(1, Ordering::Relaxed);
            return;
        }

        let start = self.rr_index.load(Ordering::Relaxed) % workers.len();
        let mut best_idx = None;
        let mut best_queued = WORKER_SEND_BUF + 1;

        for offset in 0..workers.len() {
            let idx = (start + offset) % workers.len();
            let available = workers[idx].tx.capacity();
            if available == 0 {
                continue;
            }
            let queued = WORKER_SEND_BUF.saturating_sub(available);
            if queued < best_queued {
                best_idx = Some(idx);
                best_queued = queued;
                if queued == 0 {
                    break;
                }
            }
        }

        let Some(mut index) = best_idx else {
            self.stats.dropped_packets.fetch_add(1, Ordering::Relaxed);
            return;
        };

        match workers[index].tx.try_send(packet) {
            Ok(()) => {
                workers[index].queued = best_queued + 1;
                self.stats.packets_up.fetch_add(1, Ordering::Relaxed);
                self.rr_index
                    .store(index.wrapping_add(1), Ordering::Relaxed);
            }
            Err(mpsc::error::TrySendError::Full(_)) => {
                self.stats.dropped_packets.fetch_add(1, Ordering::Relaxed);
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                workers.remove(index);
                if workers.is_empty() {
                    self.stats.dropped_packets.fetch_add(1, Ordering::Relaxed);
                    return;
                }
                if index >= workers.len() {
                    index = 0;
                }
                self.rr_index.store(index, Ordering::Relaxed);
                self.stats.dropped_packets.fetch_add(1, Ordering::Relaxed);
            }
        }
    }

    async fn write_loop(
        self: Arc<Self>,
        mut return_rx: mpsc::Receiver<Packet>,
        mut shutdown_rx: watch::Receiver<bool>,
    ) {
        loop {
            tokio::select! {
                _ = shutdown_rx.changed() => {
                    if *shutdown_rx.borrow() {
                        return;
                    }
                }
                packet = return_rx.recv() => {
                    let Some(packet) = packet else { return; };
                    let addr = self.client_addr.lock().ok().and_then(|client_addr| *client_addr);
                    let Some(addr) = addr else {
                        self.stats.dropped_packets.fetch_add(1, Ordering::Relaxed);
                        continue;
                    };
                    match self.socket.send_to(packet.as_ref(), addr).await {
                        Ok(_) => {
                            self.stats.total_bytes_down.fetch_add(packet.len() as i64, Ordering::Relaxed);
                            self.stats.packets_down.fetch_add(1, Ordering::Relaxed);
                        }
                        Err(_) => {
                            self.stats.dropped_packets.fetch_add(1, Ordering::Relaxed);
                        }
                    }
                }
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn group_loop(
    group_id: usize,
    hash_index: usize,
    worker_ids: Vec<usize>,
    get_config: bool,
    peer: SocketAddr,
    local_port: String,
    args: CoreArgs,
    params: Arc<TurnParams>,
    dispatcher: Arc<Dispatcher>,
    config_tx: mpsc::Sender<String>,
    config_sent: Arc<AtomicBool>,
    stats: Arc<Stats>,
    captcha_tx: broadcast::Sender<String>,
    auth_gate: Arc<Mutex<()>>,
    mut shutdown_rx: watch::Receiver<bool>,
    mut pause_rx: watch::Receiver<bool>,
    wait_ready: Option<watch::Receiver<bool>>,
    signal_ready: Option<watch::Sender<bool>>,
) {
    if let Some(wait_ready) = wait_ready {
        println!("[ГРУППА #{group_id}] Ожидание сигнала от предыдущей группы...");
        if wait_for_group_ready(wait_ready, shutdown_rx.clone()).await {
            return;
        }
    }

    let mut cycle_number = 0usize;
    let mut batch_shutdown: Option<watch::Sender<bool>> = None;
    let mut batch_handles: Vec<JoinHandle<()>> = Vec::new();
    let mut ready_signaled = false;

    loop {
        if *shutdown_rx.borrow() {
            stop_batch(&mut batch_shutdown, &mut batch_handles).await;
            return;
        }

        if *pause_rx.borrow() {
            stop_batch(&mut batch_shutdown, &mut batch_handles).await;
            println!("[ГРУППА #{group_id}] Пауза (Doze)");
            if wait_while_paused(&mut pause_rx, &mut shutdown_rx).await {
                return;
            }
            println!("[ГРУППА #{group_id}] Возобновление — новые креды");
        }

        let hash = args.hashes[hash_index % args.hashes.len()].clone();
        let short_hash = if hash.len() > 8 { &hash[..8] } else { &hash };
        println!(
            "[ГРУППА #{group_id}] Цикл {cycle_number}: ожидание очереди получения кредов (хеш: {short_hash}...)"
        );

        let creds = {
            let _guard = auth_gate.lock().await;
            println!("[ГРУППА #{group_id}] Цикл {cycle_number}: запрос кредов");
            match get_creds_with_fallback(&args, &hash, stats.clone(), captcha_tx.clone()).await {
                Ok(creds) => Arc::new(creds),
                Err(err) => {
                    if *shutdown_rx.borrow() {
                        return;
                    }
                    stats.creds_errors.fetch_add(1, Ordering::Relaxed);
                    println!("[ГРУППА #{group_id}] Ошибка кредов: {err:#}");
                    if wait_or_shutdown(Duration::from_secs(30), &mut shutdown_rx).await {
                        return;
                    }
                    continue;
                }
            }
        };

        let sleep_secs = if creds.lifetime_seconds > 120 {
            (creds.lifetime_seconds - 120) as u64
        } else {
            DEFAULT_CYCLE_SECS
        };
        println!(
            "[ГРУППА #{group_id}] Креды OK, TURN: {:?}, {} воркеров, до смены кредов: {} сек",
            creds.turn_urls,
            worker_ids.len(),
            sleep_secs
        );

        stop_batch(&mut batch_shutdown, &mut batch_handles).await;
        let (batch_shutdown_tx, batch_shutdown_rx) = watch::channel(false);
        batch_shutdown = Some(batch_shutdown_tx);
        let (event_tx, mut event_rx) = mpsc::channel::<WorkerEvent>(worker_ids.len().max(1) * 2);
        let mut quota_workers: HashSet<usize> = HashSet::new();
        let mut dead_workers: HashSet<usize> = HashSet::new();
        let config_request_in_flight = Arc::new(AtomicBool::new(false));

        for (index, worker_id) in worker_ids.iter().copied().enumerate() {
            let delay = Duration::from_millis((index as u64) * 500);
            let worker_args = args.clone();
            let worker_params = params.clone();
            let worker_creds = creds.clone();
            let worker_dispatcher = dispatcher.clone();
            let worker_stats = stats.clone();
            let worker_config_tx = config_tx.clone();
            let worker_config_sent = config_sent.clone();
            let worker_config_request_in_flight = config_request_in_flight.clone();
            let worker_shutdown = shutdown_rx.clone();
            let worker_batch_shutdown = batch_shutdown_rx.clone();
            let worker_local_port = local_port.clone();
            let worker_event_tx = event_tx.clone();
            batch_handles.push(tokio::spawn(async move {
                if !delay.is_zero() {
                    tokio::select! {
                        _ = time::sleep(delay) => {}
                        _ = wait_for_any_shutdown(worker_shutdown.clone(), worker_batch_shutdown.clone()) => return,
                    }
                }
                worker_loop(
                    worker_id,
                    peer,
                    worker_local_port,
                    worker_args,
                    worker_params,
                    worker_creds,
                    worker_dispatcher,
                    worker_config_tx,
                    worker_config_sent,
                    worker_config_request_in_flight,
                    get_config,
                    worker_stats,
                    worker_shutdown,
                    worker_batch_shutdown,
                    worker_event_tx,
                )
                .await;
            }));
        }
        drop(event_tx);

        if !ready_signaled {
            if let Some(signal_ready) = signal_ready.clone() {
                tokio::spawn(async move {
                    time::sleep(GROUP_READY_DELAY).await;
                    let _ = signal_ready.send(true);
                });
            }
            ready_signaled = true;
        }

        let ttl_sleep = time::sleep(Duration::from_secs(sleep_secs));
        tokio::pin!(ttl_sleep);
        loop {
            tokio::select! {
                _ = shutdown_rx.changed() => {
                    if *shutdown_rx.borrow() {
                        stop_batch(&mut batch_shutdown, &mut batch_handles).await;
                        return;
                    }
                }
                _ = pause_rx.changed() => {
                    if *pause_rx.borrow() {
                        println!("[ГРУППА #{group_id}] Пауза (Doze)");
                        break;
                    }
                }
                _ = &mut ttl_sleep => {
                    println!("[ГРУППА #{group_id}] TTL {} сек истёк, ротация", sleep_secs);
                    break;
                }
                event = event_rx.recv() => {
                    let Some(event) = event else {
                        println!("[ГРУППА #{group_id}] Все воркеры завершились, беру новые креды");
                        break;
                    };
                    match event.kind {
                        WorkerEventKind::StreamClosed => {
                            println!("[ГРУППА #{group_id}] Мгновенная ротация: сервер ВК закрыл поток");
                            break;
                        }
                        WorkerEventKind::Quota => {
                            quota_workers.insert(event.worker_id);
                            let threshold = worker_ids.len().min(5).max(1);
                            if quota_workers.len() >= threshold {
                                println!(
                                    "[ГРУППА #{group_id}] Досрочная ротация: исчерпана квота TURN у {} воркеров",
                                    quota_workers.len()
                                );
                                break;
                            }
                        }
                        WorkerEventKind::CredsDead => {
                            dead_workers.insert(event.worker_id);
                            let threshold = worker_ids.len().min(8).max(1);
                            if dead_workers.len() >= threshold {
                                println!(
                                    "[ГРУППА #{group_id}] Досрочная ротация: сервер ВК убил сессию у {} воркеров",
                                    dead_workers.len()
                                );
                                break;
                            }
                        }
                    }
                }
            }
        }

        cycle_number += 1;
    }
}

async fn stop_batch(
    batch_shutdown: &mut Option<watch::Sender<bool>>,
    handles: &mut Vec<JoinHandle<()>>,
) {
    if let Some(tx) = batch_shutdown.take() {
        let _ = tx.send(true);
    }
    for handle in handles.drain(..) {
        let _ = time::timeout(Duration::from_secs(3), handle).await;
    }
}

async fn wait_for_group_ready(
    mut ready_rx: watch::Receiver<bool>,
    mut shutdown_rx: watch::Receiver<bool>,
) -> bool {
    loop {
        if *shutdown_rx.borrow() {
            return true;
        }
        if *ready_rx.borrow() {
            return false;
        }
        tokio::select! {
            _ = shutdown_rx.changed() => {}
            _ = ready_rx.changed() => {}
        }
    }
}

async fn wait_while_paused(
    pause_rx: &mut watch::Receiver<bool>,
    shutdown_rx: &mut watch::Receiver<bool>,
) -> bool {
    loop {
        if *shutdown_rx.borrow() {
            return true;
        }
        if !*pause_rx.borrow() {
            return false;
        }
        tokio::select! {
            _ = shutdown_rx.changed() => {}
            _ = pause_rx.changed() => {}
        }
    }
}

async fn wait_or_shutdown(duration: Duration, shutdown_rx: &mut watch::Receiver<bool>) -> bool {
    tokio::select! {
        _ = shutdown_rx.changed() => *shutdown_rx.borrow(),
        _ = time::sleep(duration) => false,
    }
}

async fn wait_for_any_shutdown(
    mut shutdown_rx: watch::Receiver<bool>,
    mut batch_shutdown_rx: watch::Receiver<bool>,
) {
    loop {
        if *shutdown_rx.borrow() || *batch_shutdown_rx.borrow() {
            return;
        }
        tokio::select! {
            _ = shutdown_rx.changed() => {}
            _ = batch_shutdown_rx.changed() => {}
        }
    }
}

async fn worker_loop(
    worker_id: usize,
    peer: SocketAddr,
    local_port: String,
    args: CoreArgs,
    params: Arc<TurnParams>,
    creds: Arc<Credentials>,
    dispatcher: Arc<Dispatcher>,
    config_tx: mpsc::Sender<String>,
    config_sent: Arc<AtomicBool>,
    config_request_in_flight: Arc<AtomicBool>,
    group_get_config: bool,
    stats: Arc<Stats>,
    mut shutdown_rx: watch::Receiver<bool>,
    mut batch_shutdown_rx: watch::Receiver<bool>,
    event_tx: mpsc::Sender<WorkerEvent>,
) {
    let mut attempt = 0usize;
    loop {
        if *shutdown_rx.borrow() || *batch_shutdown_rx.borrow() {
            return;
        }
        let get_config = group_get_config
            && !config_sent.load(Ordering::Relaxed)
            && config_request_in_flight
                .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                .is_ok();
        let result = run_session(
            worker_id,
            peer,
            &local_port,
            &args,
            &params,
            &creds,
            dispatcher.clone(),
            if get_config {
                Some(config_tx.clone())
            } else {
                None
            },
            config_sent.clone(),
            stats.clone(),
            shutdown_rx.clone(),
            batch_shutdown_rx.clone(),
        )
        .await;

        if get_config && !config_sent.load(Ordering::Relaxed) {
            config_request_in_flight.store(false, Ordering::Release);
        }

        if let Err(err) = result {
            if *shutdown_rx.borrow() || *batch_shutdown_rx.borrow() {
                return;
            }
            attempt += 1;
            let text = err.to_string();
            if text.contains("FATAL_AUTH") {
                eprintln!("[ВОРКЕР #{worker_id}] Фатальная ошибка: {err:#}");
                return;
            }
            if let Some(kind) = classify_session_error(&text) {
                let _ = event_tx.send(WorkerEvent { worker_id, kind }).await;
                if matches!(kind, WorkerEventKind::Quota) {
                    println!("[ВОРКЕР #{worker_id}] Ошибка квоты TURN: {err:#}");
                    return;
                }
            }
            println!("[ВОРКЕР #{worker_id}] Ошибка (попытка {attempt}): {err:#}");
            stats.reconnects.fetch_add(1, Ordering::Relaxed);
        }

        let delay = Duration::from_secs(5 + (worker_id as u64 % 7));
        tokio::select! {
            _ = shutdown_rx.changed() => {
                if *shutdown_rx.borrow() {
                    return;
                }
            }
            _ = batch_shutdown_rx.changed() => {
                if *batch_shutdown_rx.borrow() {
                    return;
                }
            }
            _ = time::sleep(delay) => {}
        }
    }
}

fn classify_session_error(text: &str) -> Option<WorkerEventKind> {
    let lower = text.to_lowercase();
    if lower.contains("turn квота") || lower.contains("quota") {
        return Some(WorkerEventKind::Quota);
    }
    if lower.contains("stream closed") {
        return Some(WorkerEventKind::StreamClosed);
    }
    if lower.contains("attribute not found")
        || lower.contains("rate limit")
        || lower.contains("flood control")
        || lower.contains("ip mismatch")
        || lower.contains("error 29")
        || lower.contains("unauthorized")
        || lower.contains("allocation mismatch")
        || lower.contains("error 508")
        || lower.contains("cannot create socket")
    {
        return Some(WorkerEventKind::CredsDead);
    }
    None
}

async fn run_session(
    worker_id: usize,
    peer: SocketAddr,
    local_port: &str,
    args: &CoreArgs,
    params: &TurnParams,
    creds: &Credentials,
    dispatcher: Arc<Dispatcher>,
    config_tx: Option<mpsc::Sender<String>>,
    config_sent: Arc<AtomicBool>,
    stats: Arc<Stats>,
    mut shutdown_rx: watch::Receiver<bool>,
    mut batch_shutdown_rx: watch::Receiver<bool>,
) -> Result<()> {
    let selected_url = creds
        .turn_urls
        .get(worker_id % creds.turn_urls.len())
        .context("нет TURN URL в учетных данных")?;
    let turn_addr = override_turn_addr(selected_url, params)?;
    let resolved_turn = resolve_socket_addr(&turn_addr).context("резолв TURN")?;
    let proto = if args.use_udp { "UDP" } else { "TCP" };
    println!("[СЕССИЯ #{worker_id}] TURN {resolved_turn} ({proto})");

    let turn_conn: Arc<dyn Conn + Send + Sync> = if args.use_udp {
        let bind_addr = if resolved_turn.is_ipv4() {
            "0.0.0.0:0"
        } else {
            "[::]:0"
        };
        Arc::new(UdpSocket::bind(bind_addr).await.context("TURN UDP bind")?)
    } else {
        Arc::new(
            time::timeout(Duration::from_secs(10), TcpTurnConn::connect(resolved_turn))
                .await
                .context("подключение TURN TCP: timeout")?
                .context("подключение TURN TCP")?,
        )
    };

    let client = Client::new(ClientConfig {
        stun_serv_addr: resolved_turn.to_string(),
        turn_serv_addr: resolved_turn.to_string(),
        username: creds.user.clone(),
        password: creds.pass.clone(),
        realm: String::new(),
        software: String::new(),
        rto_in_ms: 0,
        conn: turn_conn,
        vnet: None,
    })
    .await
    .context("TURN клиент")?;
    client.listen().await.context("TURN Listen")?;

    let relay = match client.allocate().await {
        Ok(relay) => relay,
        Err(err) => {
            let text = err.to_string();
            if text.contains("Quota") || text.contains("486") {
                return Err(anyhow::anyhow!("TURN квота: {err}"));
            }
            return Err(err).context("TURN Allocate");
        }
    };
    println!(
        "[СЕССИЯ #{worker_id}] Relay: {}",
        relay
            .local_addr()
            .map(|addr| addr.to_string())
            .unwrap_or_default()
    );

    let relay: Arc<dyn Conn + Send + Sync> = Arc::new(relay);
    let peer_conn: Arc<dyn Conn + Send + Sync> = Arc::new(PeerConn { relay, peer });
    let sni = if params.sni.is_empty() {
        "calls.okcdn.ru".to_string()
    } else {
        params.sni.clone()
    };
    let certificate = Certificate::generate_self_signed(vec!["localhost".to_string()])
        .context("генерация сертификата")?;
    let dtls_config = Config {
        certificates: vec![certificate],
        insecure_skip_verify: true,
        extended_master_secret: ExtendedMasterSecretType::Require,
        cipher_suites: vec![CipherSuiteId::Tls_Ecdhe_Ecdsa_With_Aes_128_Gcm_Sha256],
        server_name: sni,
        mtu: 1280,
        ..Default::default()
    };

    println!("[ВОРКЕР #{worker_id}] [DTLS] Рукопожатие (Handshake)...");
    let dtls = time::timeout(
        Duration::from_secs(45),
        DTLSConn::new(peer_conn, dtls_config, true, None),
    )
    .await
    .context("DTLS хендшейк: timeout")?
    .context("DTLS клиент")?;
    let dtls: Arc<dyn Conn + Send + Sync> = Arc::new(dtls);
    println!("[ВОРКЕР #{worker_id}] [DTLS] Соединение установлено ✓");

    stats.active_connections.fetch_add(1, Ordering::Relaxed);
    let active_guard = ActiveGuard {
        stats: stats.clone(),
    };

    if let Some(config_tx) = config_tx {
        match request_config(dtls.clone(), local_port, &args.device_id, &args.password).await {
            Ok(Some(config)) => {
                if !config_sent.swap(true, Ordering::Relaxed) {
                    let _ = config_tx.try_send(config);
                    println!("[ВОРКЕР #{worker_id}] Конфиг получен");
                }
            }
            Ok(None) => {
                println!(
                    "[ВОРКЕР #{worker_id}] Сервер ещё не выдал WireGuard-конфиг, повторим позже"
                );
            }
            Err(err) => {
                if err.to_string().contains("FATAL_AUTH") {
                    return Err(err);
                }
                println!("[ВОРКЕР #{worker_id}] Ошибка конфига: {err:#}");
            }
        }
    }

    println!("[ВОРКЕР #{worker_id}] [READY] Туннель готов к работе ✓");
    let mut outgoing_rx = dispatcher.register(worker_id);

    let mut keepalive = time::interval(args.keepalive);
    let mut binding_keepalive = time::interval(args.keepalive);
    keepalive.set_missed_tick_behavior(MissedTickBehavior::Delay);
    binding_keepalive.set_missed_tick_behavior(MissedTickBehavior::Delay);
    let mut buf = vec![0u8; READ_BUF_SIZE];
    let mut last_payload_write = Instant::now();
    let mut has_sent_payload = false;
    let mut session_error = None;

    loop {
        tokio::select! {
            _ = shutdown_rx.changed() => {
                if *shutdown_rx.borrow() {
                    break;
                }
            }
            _ = batch_shutdown_rx.changed() => {
                if *batch_shutdown_rx.borrow() {
                    break;
                }
            }
            _ = binding_keepalive.tick(), if args.use_udp => {
                let _ = time::timeout(Duration::from_secs(3), client.send_binding_request()).await;
            }
            _ = keepalive.tick() => {
                if has_sent_payload && last_payload_write.elapsed() >= args.keepalive {
                    if let Err(err) = dtls.send(WAKEUP_PACKET).await {
                        session_error = Some(anyhow::anyhow!("Ошибка Writer (WAKEUP): {err}"));
                        break;
                    }
                }
            }
            packet = outgoing_rx.recv() => {
                let Some(packet) = packet else { break; };
                if let Err(err) = dtls.send(packet.as_ref()).await {
                    session_error = Some(anyhow::anyhow!("Ошибка Writer (Payload): {err}"));
                    break;
                }
                last_payload_write = Instant::now();
                has_sent_payload = true;
            }
            read = time::timeout(Duration::from_secs(60), dtls.recv(&mut buf)) => {
                let n = match read {
                    Ok(Ok(n)) => n,
                    Ok(Err(err)) => {
                        session_error = Some(anyhow::anyhow!("Ошибка Reader: {err}"));
                        break;
                    }
                    Err(_) => continue,
                };
                if &buf[..n] == WAKEUP_PACKET {
                    continue;
                }
                if dispatcher.return_tx.send(Bytes::copy_from_slice(&buf[..n])).await.is_err() {
                    break;
                }
            }
        }
    }

    dispatcher.unregister(worker_id);
    let _ = dtls.close().await;
    let _ = client.close().await;
    drop(active_guard);
    println!("[СЕССИЯ #{worker_id}] Завершена");
    match session_error {
        Some(err) => Err(err),
        None => Ok(()),
    }
}

struct ActiveGuard {
    stats: Arc<Stats>,
}

impl Drop for ActiveGuard {
    fn drop(&mut self) {
        self.stats
            .active_connections
            .fetch_sub(1, Ordering::Relaxed);
    }
}

async fn request_config(
    dtls: Arc<dyn Conn + Send + Sync>,
    local_port: &str,
    device_id: &str,
    password: &str,
) -> Result<Option<String>> {
    let payload = format!("GETCONF:{local_port}|{device_id}|{password}");
    dtls.send(payload.as_bytes())
        .await
        .context("отправка GETCONF")?;

    let mut buf = vec![0u8; 4096];
    let n = time::timeout(Duration::from_secs(15), dtls.recv(&mut buf))
        .await
        .context("чтение ответа конфига: timeout")?
        .context("чтение ответа конфига")?;
    let response = String::from_utf8_lossy(&buf[..n]).to_string();
    if response == "NOCONF" {
        return Ok(None);
    }
    if let Some(reason) = response.strip_prefix("DENIED:") {
        match reason {
            "wrong_password" => bail!("FATAL_AUTH: неверный пароль подключения"),
            "expired" => bail!("FATAL_AUTH: срок действия пароля истёк"),
            "device_mismatch" => bail!("FATAL_AUTH: пароль привязан к другому устройству"),
            other => bail!("FATAL_AUTH: доступ запрещён ({other})"),
        }
    }
    Ok(Some(response))
}

async fn print_first_config(
    config_rx: &mut mpsc::Receiver<String>,
    mut shutdown_rx: watch::Receiver<bool>,
    peer_ip: IpAddr,
    split_tunnel: bool,
    no_dns: bool,
) {
    tokio::select! {
        config = config_rx.recv() => {
            let Some(raw_config) = config else { return; };
            let mut final_config = ensure_mtu(raw_config);
            if split_tunnel {
                final_config = modify_config_for_split_tunnel(&final_config, peer_ip);
            }
            if no_dns {
                final_config = remove_dns_from_config(&final_config);
            }
            println!();
            println!("╔══════════════ WireGuard Конфиг ══════════════╗");
            for line in final_config.lines() {
                println!("║ {:<44} ║", line);
            }
            println!("╚══════════════════════════════════════════════╝");
            if let Err(err) = tokio::fs::write("wg-turn.conf", format!("{final_config}\n")).await {
                println!("[КОНФИГ] Ошибка сохранения: {err}");
            } else {
                println!("[КОНФИГ] Сохранён в wg-turn.conf");
            }
        }
        _ = shutdown_rx.changed() => {}
    }
}

fn ensure_mtu(config: String) -> String {
    if config.contains("MTU =") {
        return config;
    }
    let mut lines = Vec::new();
    for line in config.lines() {
        lines.push(line.to_string());
        if line.trim() == "[Interface]" {
            lines.push("MTU = 1280".to_string());
        }
    }
    lines.join("\n")
}

fn remove_dns_from_config(config: &str) -> String {
    config
        .lines()
        .filter(|line| !line.trim_start().starts_with("DNS ="))
        .collect::<Vec<_>>()
        .join("\n")
}

fn modify_config_for_split_tunnel(config: &str, peer_ip: IpAddr) -> String {
    let mut excludes = Vec::new();
    if let IpAddr::V4(ip) = peer_ip {
        excludes.push((u32::from(ip), 32));
    }

    for cidr in [
        "95.163.0.0/16",
        "87.240.0.0/16",
        "93.186.224.0/20",
        "185.32.248.0/22",
        "185.29.130.0/24",
        "217.20.144.0/20",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
    ] {
        if let Some(parsed) = parse_cidr_v4(cidr) {
            excludes.push(parsed);
        }
    }

    let allowed = calc_allowed_ips(&excludes);
    config
        .lines()
        .map(|line| {
            if line.trim_start().starts_with("AllowedIPs") {
                format!("AllowedIPs = {allowed}")
            } else {
                line.to_string()
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn parse_cidr_v4(raw: &str) -> Option<(u32, u32)> {
    let (ip_raw, bits_raw) = raw.split_once('/')?;
    let ip = ip_raw.parse::<Ipv4Addr>().ok()?;
    let bits = bits_raw.parse::<u32>().ok()?;
    if bits > 32 {
        return None;
    }
    Some((u32::from(ip), bits))
}

fn calc_allowed_ips(excludes: &[(u32, u32)]) -> String {
    fn contains(container: (u32, u32), target: (u32, u32)) -> bool {
        if container.1 > target.1 {
            return false;
        }
        let mask = prefix_mask(container.1);
        (container.0 & mask) == (target.0 & mask)
    }

    fn overlaps(a: (u32, u32), b: (u32, u32)) -> bool {
        let mask = prefix_mask(a.1.min(b.1));
        (a.0 & mask) == (b.0 & mask)
    }

    fn split_rec(block: (u32, u32), excludes: &[(u32, u32)], result: &mut Vec<(u32, u32)>) {
        if excludes.iter().any(|exclude| contains(*exclude, block)) {
            return;
        }
        if !excludes.iter().any(|exclude| overlaps(block, *exclude)) {
            result.push(block);
            return;
        }
        if block.1 >= 32 {
            return;
        }

        let next = block.1 + 1;
        let bit = 1u32 << (32 - next);
        split_rec((block.0, next), excludes, result);
        split_rec((block.0 | bit, next), excludes, result);
    }

    let mut result = Vec::new();
    split_rec((0, 0), excludes, &mut result);
    result
        .into_iter()
        .map(|(ip, bits)| format!("{}/{}", Ipv4Addr::from(ip), bits))
        .collect::<Vec<_>>()
        .join(", ")
}

fn prefix_mask(bits: u32) -> u32 {
    if bits == 0 {
        0
    } else {
        u32::MAX << (32 - bits)
    }
}

async fn get_creds_with_fallback(
    args: &CoreArgs,
    hash: &str,
    stats: Arc<Stats>,
    captcha_tx: broadcast::Sender<String>,
) -> Result<Credentials> {
    match get_vk_creds_with_retries(args, hash, stats.clone(), captcha_tx.clone()).await {
        Ok(creds) => Ok(creds),
        Err(primary_err) => {
            if let Some(secondary) = &args.secondary_hash {
                println!("Основной хеш не сработал, пробую запасной");
                get_vk_creds_with_retries(args, secondary, stats, captcha_tx).await
            } else {
                Err(primary_err)
            }
        }
    }
}

async fn get_vk_creds_with_retries(
    args: &CoreArgs,
    hash: &str,
    stats: Arc<Stats>,
    captcha_tx: broadcast::Sender<String>,
) -> Result<Credentials> {
    let mut last_err: Option<anyhow::Error> = None;

    for attempt in 0..MAX_CREDS_RETRIES {
        match get_vk_creds_once(args, hash, captcha_tx.clone()).await {
            Ok(creds) => return Ok(creds),
            Err(err) => {
                let text = err.to_string();
                stats.creds_errors.fetch_add(1, Ordering::Relaxed);

                if text.contains("9000") || text.contains("call not found") {
                    return Err(err).context("хеш мёртв");
                }

                last_err = Some(err);
                if attempt + 1 == MAX_CREDS_RETRIES {
                    break;
                }

                let backoff = creds_retry_backoff(&text, attempt, hash);
                println!(
                    "[КРЕДЫ] Попытка {}/{} не прошла: {}; повтор через {}.{:03} сек",
                    attempt + 1,
                    MAX_CREDS_RETRIES,
                    text,
                    backoff.as_secs(),
                    backoff.subsec_millis()
                );
                time::sleep(backoff).await;
            }
        }
    }

    match last_err {
        Some(err) => Err(err).context(format!("исчерпаны {MAX_CREDS_RETRIES} попыток")),
        None => bail!("исчерпаны {MAX_CREDS_RETRIES} попыток получения кредов"),
    }
}

fn creds_retry_backoff(text: &str, attempt: usize, hash: &str) -> Duration {
    let lower = text.to_lowercase();
    if lower.contains("flood") {
        return Duration::from_secs((5 * (attempt as u64 + 1)).min(60));
    }

    let base_secs = (1u64 << attempt.min(5)).min(30);
    let jitter_ms = ((attempt as u64 * 977) + (hash.len() as u64 * 131)) % 1000;
    Duration::from_secs(base_secs) + Duration::from_millis(jitter_ms)
}

async fn get_vk_creds_once(
    args: &CoreArgs,
    hash: &str,
    captcha_tx: broadcast::Sender<String>,
) -> Result<Credentials> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .build()
        .context("создание HTTP клиента")?;
    let name = "NxiwNetwork";

    let step1 = post_form(
        &client,
        "https://login.vk.ru/?act=get_anonym_token",
        &format!(
            "client_secret={}&client_id={}&scopes=audio_anonymous%2Cvideo_anonymous%2Cphotos_anonymous%2Cprofile_anonymous&isApiOauthAnonymEnabled=false&version=1&app_id={}",
            args.vk_app_secret, args.vk_app_id, args.vk_app_id
        ),
        args,
    )
    .await
    .context("шаг 1")?;
    check_api_error(&step1, "шаг 1")?;
    let token1 = json_str(&step1, &["data", "access_token"]).context("шаг 1 парсинг")?;

    let step2 = post_form(
        &client,
        "https://login.vk.ru/?act=get_anonym_token",
        &format!(
            "client_id={}&token_type=messages&payload={}&client_secret={}&version=1&app_id={}",
            args.vk_app_id, token1, args.vk_app_secret, args.vk_app_id
        ),
        args,
    )
    .await
    .context("шаг 2")?;
    check_api_error(&step2, "шаг 2")?;
    let token3 = json_str(&step2, &["data", "access_token"]).context("шаг 2 парсинг")?;

    let mut step3_data = format!(
        "vk_join_link=https://vk.com/call/join/{hash}&name={}&access_token={token3}",
        urlencoding::encode(name)
    );
    let mut step3 = post_form(
        &client,
        "https://api.vk.ru/method/calls.getAnonymousToken?v=5.264",
        &step3_data,
        args,
    )
    .await
    .context("шаг 3")?;

    if let Some(error) = step3.get("error") {
        if json_f64(error, "error_code").unwrap_or_default() as i32 == 14 {
            let captcha = parse_captcha_error(error)?;
            let mut used_cache = false;
            let success_token = if let Some(token) = pop_cached_captcha_token() {
                println!("[КАПЧА] Пробую использовать кэшированный success_token...");
                used_cache = true;
                token
            } else {
                let token = solve_captcha(args, &captcha, captcha_tx).await?;
                println!("[КАПЧА] Сохраняю success_token в кэш для 4 следующих групп");
                push_cached_captcha_token(token.clone(), 4);
                token
            };
            let attempt = if captcha.attempt.is_empty() || captcha.attempt == "0" {
                "1".to_string()
            } else {
                captcha.attempt
            };
            step3_data = format!(
                "vk_join_link=https://vk.com/call/join/{hash}&name={}&access_token={token3}&captcha_key=&captcha_sid={}&is_sound_captcha=0&success_token={}&captcha_ts={}&captcha_attempt={}",
                urlencoding::encode(name),
                captcha.sid,
                urlencoding::encode(&success_token),
                captcha.ts,
                attempt
            );
            step3 = post_form(
                &client,
                "https://api.vk.ru/method/calls.getAnonymousToken?v=5.264",
                &step3_data,
                args,
            )
            .await
            .context("шаг 3 после капчи")?;
            if let Some(error_after_captcha) = step3.get("error") {
                if json_f64(error_after_captcha, "error_code").unwrap_or_default() as i32 == 14 {
                    if used_cache {
                        println!("[КАПЧА] Кэшированный токен отклонён API");
                        invalidate_cached_captcha_token();
                        bail!("кэшированный токен отклонён");
                    }
                    bail!("капча не пройдена после решения");
                }
                bail!("VK API error после капчи: {error_after_captcha}");
            }
            println!("[КАПЧА] УСПЕХ: токен получен после решения капчи");
        } else {
            bail!("VK API error: {error}");
        }
    }

    check_api_error(&step3, "шаг 3")?;
    let token4 = json_str(&step3, &["response", "token"]).context("шаг 3 парсинг")?;

    let step4 = post_form(
        &client,
        "https://calls.okcdn.ru/fb.do",
        &format!(
            "session_data=%7B%22version%22%3A2%2C%22device_id%22%3A%22{}%22%2C%22client_version%22%3A1.1%2C%22client_type%22%3A%22SDK_JS%22%7D&method=auth.anonymLogin&format=JSON&application_key={}",
            uuid::Uuid::new_v4(),
            OK_APP_KEY
        ),
        args,
    )
    .await
    .context("шаг 4")?;
    check_api_error(&step4, "шаг 4")?;
    let session_key = json_str(&step4, &["session_key"]).context("шаг 4 парсинг")?;

    let step5 = post_form(
        &client,
        "https://calls.okcdn.ru/fb.do",
        &format!(
            "joinLink={hash}&isVideo=false&protocolVersion=5&anonymToken={token4}&method=vchat.joinConversationByLink&format=JSON&application_key={OK_APP_KEY}&session_key={session_key}"
        ),
        args,
    )
    .await
    .context("шаг 5")?;
    check_api_error(&step5, "шаг 5")?;

    let turn_server = step5
        .get("turn_server")
        .and_then(Value::as_object)
        .context("turn_server не найден в ответе")?;
    let user = turn_server
        .get("username")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    let pass = turn_server
        .get("credential")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    if user.is_empty() || pass.is_empty() {
        bail!("пустые credentials в ответе");
    }

    let lifetime_seconds = turn_server
        .get("lifetime")
        .or_else(|| turn_server.get("ttl"))
        .and_then(Value::as_f64)
        .unwrap_or_default() as i64;

    let urls = turn_server
        .get("urls")
        .and_then(Value::as_array)
        .context("нет TURN urls в ответе")?;
    let turn_urls: Vec<String> = urls
        .iter()
        .filter_map(Value::as_str)
        .map(clean_turn_url)
        .filter(|addr| !addr.is_empty())
        .collect();
    if turn_urls.is_empty() {
        bail!("нет TURN urls в ответе");
    }

    println!("[ВК] Креды получены ✓");
    Ok(Credentials {
        user,
        pass,
        turn_urls,
        lifetime_seconds,
    })
}

async fn post_form(
    client: &reqwest::Client,
    url: &str,
    body: &str,
    args: &CoreArgs,
) -> Result<Value> {
    let mut request = client
        .post(url)
        .header("User-Agent", &args.user_agent)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("sec-ch-ua-platform", "\"Android\"")
        .header(
            "sec-ch-ua",
            "\"Not(A:Brand\";v=\"99\", \"Android WebView\";v=\"133\", \"Chromium\";v=\"133\"",
        )
        .header("sec-ch-ua-mobile", "?1")
        .header("Sec-Fetch-Site", "cross-site")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Dest", "empty")
        .header("Accept", "*/*")
        .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
        .body(body.to_string());

    if url.contains("api.vk.ru") {
        request = request
            .header("Origin", "https://vk.com")
            .header("Referer", "https://vk.com/");
    } else {
        request = request
            .header("Origin", "https://login.vk.ru")
            .header("Referer", "https://login.vk.ru/");
    }

    let response = request.send().await.context("HTTP request")?;
    let text = response.text().await.context("чтение ответа")?;
    serde_json::from_str(&text).with_context(|| format!("парсинг JSON: {text}"))
}

fn check_api_error(value: &Value, step: &str) -> Result<()> {
    if let Some(error) = value.get("error") {
        bail!("{step} API error: {error}");
    }
    Ok(())
}

fn json_str(value: &Value, path: &[&str]) -> Result<String> {
    let mut current = value;
    for key in path {
        current = current.get(*key).with_context(|| {
            let keys = current
                .as_object()
                .map(|object| object.keys().cloned().collect::<Vec<_>>().join(", "))
                .unwrap_or_else(|| current.to_string());
            format!("path {key:?} not found; available: [{keys}]")
        })?;
    }
    current
        .as_str()
        .map(ToString::to_string)
        .context("value at path is not string")
}

fn json_f64(value: &Value, key: &str) -> Option<f64> {
    value.get(key).and_then(Value::as_f64)
}

struct CaptchaError {
    sid: String,
    redirect_uri: String,
    session_token: String,
    ts: String,
    attempt: String,
}

struct CaptchaTokenCache {
    token: String,
    usages: i32,
}

static CAPTCHA_TOKEN_CACHE: std::sync::Mutex<CaptchaTokenCache> =
    std::sync::Mutex::new(CaptchaTokenCache {
        token: String::new(),
        usages: 0,
    });

fn pop_cached_captcha_token() -> Option<String> {
    let mut cache = CAPTCHA_TOKEN_CACHE.lock().ok()?;
    if cache.usages <= 0 || cache.token.is_empty() {
        return None;
    }
    cache.usages -= 1;
    Some(cache.token.clone())
}

fn push_cached_captcha_token(token: String, usages: i32) {
    if let Ok(mut cache) = CAPTCHA_TOKEN_CACHE.lock() {
        cache.token = token;
        cache.usages = usages;
    }
}

fn invalidate_cached_captcha_token() {
    if let Ok(mut cache) = CAPTCHA_TOKEN_CACHE.lock() {
        cache.token.clear();
        cache.usages = 0;
    }
}

fn parse_captcha_error(value: &Value) -> Result<CaptchaError> {
    let redirect_uri = value
        .get("redirect_uri")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    let session_token = extract_query_param(&redirect_uri, "session_token");
    if session_token.is_empty() {
        bail!("капча без session_token");
    }
    Ok(CaptchaError {
        sid: value_to_string(value.get("captcha_sid")),
        redirect_uri,
        session_token,
        ts: value_to_string(value.get("captcha_ts")),
        attempt: value_to_string(value.get("captcha_attempt")),
    })
}

async fn solve_captcha(
    args: &CoreArgs,
    captcha: &CaptchaError,
    captcha_tx: broadcast::Sender<String>,
) -> Result<String> {
    match args.captcha_mode.as_str() {
        "wv" => {
            println!("[КАПЧА] Режим: WebView");
            solve_captcha_via_webview(captcha, captcha_tx).await
        }
        "rjs_slider" => {
            println!("[КАПЧА] Режим: RJS Slider (Rust: classic first, WV fallback)");
            match solve_captcha_via_rjs_classic(args, captcha).await {
                Ok(token) => Ok(token),
                Err(err) => {
                    println!("[КАПЧА] RJS Slider fallback: classic не прошёл: {err:#}");
                    solve_captcha_via_webview(captcha, captcha_tx).await
                }
            }
        }
        _ => {
            println!("[КАПЧА] Режим: RJS Classic");
            solve_captcha_via_rjs_classic(args, captcha).await
        }
    }
}

async fn solve_captcha_via_rjs_classic(args: &CoreArgs, captcha: &CaptchaError) -> Result<String> {
    println!("[КАПЧА] RJS: Загрузка страницы капчи...");
    let (pow_input, difficulty) = fetch_pow_input(&captcha.redirect_uri, &args.user_agent).await?;
    let mut rng = SimpleRng::new(captcha_seed(args, captcha));
    let timing = CaptchaTiming::generate(&mut rng);

    println!("[КАПЧА] RJS: Человек осматривает страницу капчи...");
    time::sleep(Duration::from_millis(timing.read_captcha_ms)).await;

    println!("[КАПЧА] RJS: Решение PoW...");
    let hash = tokio::task::spawn_blocking(move || solve_pow(&pow_input, difficulty))
        .await
        .context("PoW task join")?;
    if hash.is_empty() {
        bail!("PoW не найден");
    }
    time::sleep(Duration::from_millis(timing.fetch_pow_ms)).await;

    println!("[КАПЧА] RJS: Отправка данных...");
    let token = call_captcha_not_robot(args, &captcha.session_token, &hash, &mut rng, &timing)
        .await
        .context("ошибка captchaNotRobot API")?;

    println!("[КАПЧА] RJS: Завершение сессии...");
    time::sleep(Duration::from_millis(timing.end_session_ms)).await;
    let base = format!(
        "session_token={}&domain=vk.com&adFp=&access_token=",
        urlencoding::encode(&captcha.session_token)
    );
    let _ = captcha_api_request("captchaNotRobot.endSession", &base, captcha_user_agent()).await;

    println!("[КАПЧА] RJS: Капча решена успешно ✓");
    Ok(token)
}

async fn fetch_pow_input(redirect_uri: &str, user_agent: &str) -> Result<(String, usize)> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .build()
        .context("создание HTTP клиента капчи")?;
    let html = client
        .get(redirect_uri)
        .header("User-Agent", user_agent)
        .header(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        .send()
        .await
        .context("загрузка HTML капчи")?
        .text()
        .await
        .context("чтение HTML капчи")?;
    parse_pow_input(&html)
}

fn parse_pow_input(html: &str) -> Result<(String, usize)> {
    let pow_input = extract_between(html, "const powInput", "\"", "\"")
        .or_else(|| extract_between(html, "powInput", "\"", "\""))
        .context("powInput не найден в HTML капчи")?;
    let difficulty = html
        .split("startsWith('0'.repeat(")
        .nth(1)
        .and_then(|tail| tail.split(')').next())
        .and_then(|value| value.parse::<usize>().ok())
        .unwrap_or(2);
    Ok((pow_input, difficulty))
}

fn extract_between(text: &str, anchor: &str, left: &str, right: &str) -> Option<String> {
    let after_anchor = text.split_once(anchor)?.1;
    let after_left = after_anchor.split_once(left)?.1;
    let value = after_left.split_once(right)?.0;
    Some(value.to_string())
}

fn solve_pow(pow_input: &str, difficulty: usize) -> String {
    let target = "0".repeat(difficulty);
    for nonce in 1..=10_000_000usize {
        let mut hasher = Sha256::new();
        hasher.update(pow_input.as_bytes());
        hasher.update(nonce.to_string().as_bytes());
        let hash = format!("{:x}", hasher.finalize());
        if hash.starts_with(&target) {
            return hash;
        }
    }
    String::new()
}

async fn call_captcha_not_robot(
    args: &CoreArgs,
    session_token: &str,
    hash: &str,
    rng: &mut SimpleRng,
    timing: &CaptchaTiming,
) -> Result<String> {
    let captcha_browser_fp = format!("{:016x}{:016x}", rng.next_u64(), rng.next_u64());
    let captcha_device_json = r#"{"screenWidth":1920,"screenHeight":1080,"screenAvailWidth":1920,"screenAvailHeight":1032,"innerWidth":1920,"innerHeight":945,"devicePixelRatio":1,"language":"en-US","languages":["en-US"],"webdriver":false,"hardwareConcurrency":16,"deviceMemory":8,"connectionEffectiveType":"4g","notificationsPermission":"denied"}"#;
    let base = format!(
        "session_token={}&domain=vk.com&adFp=&access_token=",
        urlencoding::encode(session_token)
    );

    println!("[КАПЧА]   Шаг 1/4: settings...");
    captcha_api_request("captchaNotRobot.settings", &base, captcha_user_agent()).await?;

    println!("[КАПЧА]   ...пауза: изучение виджета...");
    time::sleep(Duration::from_millis(timing.settings_to_component_ms)).await;

    println!("[КАПЧА]   Шаг 2/4: componentDone...");
    let component_done = format!(
        "{base}&browser_fp={}&device={}",
        captcha_browser_fp,
        urlencoding::encode(captcha_device_json)
    );
    captcha_api_request(
        "captchaNotRobot.componentDone",
        &component_done,
        captcha_user_agent(),
    )
    .await?;

    println!("[КАПЧА]   ...пауза: движение мыши к чекбоксу + клик...");
    time::sleep(Duration::from_millis(timing.component_to_check_ms)).await;
    if timing.extra_pause_ms > 0 {
        println!("[КАПЧА]   ...дополнительная пауза: человек завис...");
        time::sleep(Duration::from_millis(timing.extra_pause_ms)).await;
    }

    println!("[КАПЧА]   Шаг 3/4: check...");
    let answer = base64::engine::general_purpose::STANDARD.encode(b"{}");
    let check_data = format!(
        "{base}&accelerometer={}&gyroscope={}&motion={}&cursor={}&taps={}&connectionRtt={}&connectionDownlink={}&browser_fp={}&hash={}&answer={}&debug_info={}",
        urlencoding::encode("[]"),
        urlencoding::encode("[]"),
        urlencoding::encode("[]"),
        urlencoding::encode(&generate_captcha_cursor(rng)),
        urlencoding::encode("[]"),
        urlencoding::encode(&generate_connection_rtt(rng)),
        urlencoding::encode(&generate_downlink(rng)),
        captcha_browser_fp,
        hash,
        answer,
        generate_debug_info(&args.device_id),
    );
    let check =
        captcha_api_request("captchaNotRobot.check", &check_data, captcha_user_agent()).await?;
    let response = check
        .get("response")
        .and_then(Value::as_object)
        .with_context(|| format!("invalid check response: {check}"))?;
    let status = response
        .get("status")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if status != "OK" {
        bail!("check status: {status}");
    }
    let token = response
        .get("success_token")
        .and_then(Value::as_str)
        .filter(|token| !token.is_empty())
        .context("success_token not found")?;

    time::sleep(Duration::from_millis(timing.check_to_end_ms)).await;
    Ok(token.to_string())
}

async fn captcha_api_request(method: &str, body: &str, user_agent: &str) -> Result<Value> {
    let url = format!("https://api.vk.ru/method/{method}?v=5.131");
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .build()
        .context("создание HTTP клиента captcha API")?;
    let text = client
        .post(url)
        .header("User-Agent", user_agent)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Origin", "https://id.vk.ru")
        .header("Referer", "https://id.vk.ru/")
        .header("sec-ch-ua-platform", "\"Windows\"")
        .header(
            "sec-ch-ua",
            "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"",
        )
        .header("sec-ch-ua-mobile", "?0")
        .header("Sec-Fetch-Site", "same-site")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Dest", "empty")
        .header("DNT", "1")
        .header("Priority", "u=1, i")
        .header("Accept", "*/*")
        .header("Accept-Language", "en-US,en;q=0.9")
        .body(body.to_string())
        .send()
        .await
        .with_context(|| format!("{method} request"))?
        .text()
        .await
        .with_context(|| format!("{method} response body"))?;
    serde_json::from_str(&text).with_context(|| format!("{method} JSON: {text}"))
}

fn captcha_user_agent() -> &'static str {
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
}

fn generate_debug_info(device_id: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(device_id.as_bytes());
    hasher.update(b"_debug_info_static_salt_v2");
    format!("{:x}", hasher.finalize())
}

fn captcha_seed(args: &CoreArgs, captcha: &CaptchaError) -> u64 {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos() as u64)
        .unwrap_or(0);
    let mut hasher = Sha256::new();
    hasher.update(args.device_id.as_bytes());
    hasher.update(captcha.session_token.as_bytes());
    let digest = hasher.finalize();
    let mut seed = nanos;
    for byte in digest.iter().take(8) {
        seed = seed.rotate_left(5) ^ (*byte as u64);
    }
    seed
}

struct SimpleRng {
    state: u64,
}

impl SimpleRng {
    fn new(seed: u64) -> Self {
        Self { state: seed | 1 }
    }

    fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.state = x;
        x
    }

    fn next_f64(&mut self) -> f64 {
        (self.next_u64() as f64) / (u64::MAX as f64)
    }

    fn gen_range_u64(&mut self, start: u64, end: u64) -> u64 {
        if end <= start {
            return start;
        }
        start + (self.next_u64() % (end - start))
    }
}

struct CaptchaTiming {
    fetch_pow_ms: u64,
    read_captcha_ms: u64,
    settings_to_component_ms: u64,
    component_to_check_ms: u64,
    check_to_end_ms: u64,
    end_session_ms: u64,
    extra_pause_ms: u64,
}

impl CaptchaTiming {
    fn generate(rng: &mut SimpleRng) -> Self {
        let fetch_pow_ms = 600 + rng.gen_range_u64(0, 800);
        let read_captcha_ms = 700 + rng.gen_range_u64(0, 1200);
        let mut settings_to_component_ms = 800 + rng.gen_range_u64(0, 1200);
        let mut component_to_check_ms = 1500 + rng.gen_range_u64(0, 2000);
        let mut check_to_end_ms = 400 + rng.gen_range_u64(0, 800);
        let end_session_ms = 100 + rng.gen_range_u64(0, 200);
        let extra_pause_ms = if rng.next_f64() < 0.10 {
            800 + rng.gen_range_u64(0, 1500)
        } else {
            0
        };

        let total = fetch_pow_ms
            + read_captcha_ms
            + settings_to_component_ms
            + component_to_check_ms
            + check_to_end_ms
            + end_session_ms
            + extra_pause_ms;
        if total < 5000 {
            let deficit = 5000 - total + rng.gen_range_u64(0, 1000);
            component_to_check_ms += deficit * 40 / 100;
            settings_to_component_ms += deficit * 25 / 100;
            check_to_end_ms += deficit * 15 / 100;
        }

        Self {
            fetch_pow_ms,
            read_captcha_ms,
            settings_to_component_ms,
            component_to_check_ms,
            check_to_end_ms,
            end_session_ms,
            extra_pause_ms,
        }
    }
}

fn generate_captcha_cursor(rng: &mut SimpleRng) -> String {
    let start_x = 200.0 + rng.next_f64() * 1520.0;
    let start_y = 200.0 + rng.next_f64() * 680.0;
    let target_x = 960.0 + (rng.next_f64() - 0.5) * 200.0;
    let target_y = 540.0 + (rng.next_f64() - 0.5) * 100.0 + 30.0;
    let cp1x = start_x + (rng.next_f64() - 0.5) * 500.0;
    let cp1y = start_y + (rng.next_f64() - 0.5) * 300.0;
    let cp2x = target_x + (rng.next_f64() - 0.5) * 150.0;
    let cp2y = target_y + (rng.next_f64() - 0.5) * 80.0;
    let points = 6 + rng.gen_range_u64(0, 7) as usize;
    let mut out = Vec::with_capacity(points);
    for i in 0..points {
        let t = i as f64 / (points.saturating_sub(1).max(1) as f64);
        let mt = 1.0 - t;
        let mut x = mt * mt * mt * start_x
            + 3.0 * mt * mt * t * cp1x
            + 3.0 * mt * t * t * cp2x
            + t * t * t * target_x;
        let mut y = mt * mt * mt * start_y
            + 3.0 * mt * mt * t * cp1y
            + 3.0 * mt * t * t * cp2y
            + t * t * t * target_y;
        x += rng.next_f64() * 3.0 - 1.5;
        y += rng.next_f64() * 3.0 - 1.5;
        out.push(format!(r#"{{"x":{x:.1},"y":{y:.1}}}"#));
    }
    format!("[{}]", out.join(","))
}

fn generate_downlink(rng: &mut SimpleRng) -> String {
    let count = 7 + rng.gen_range_u64(0, 10) as usize;
    let base = 10.0 + rng.next_f64() * 20.0;
    let stabilize_after = 2 + rng.gen_range_u64(0, 3) as usize;
    let mut values = Vec::with_capacity(count);
    for i in 0..count {
        let value = if i < stabilize_after {
            base * (0.85 + rng.next_f64() * 0.3)
        } else {
            base * (0.98 + rng.next_f64() * 0.04)
        };
        values.push(format!("{value:.1}"));
    }
    format!("[{}]", values.join(","))
}

fn generate_connection_rtt(rng: &mut SimpleRng) -> String {
    let count = 4 + rng.gen_range_u64(0, 6) as usize;
    let base = 45.0 + rng.next_f64() * 80.0;
    let mut values = Vec::with_capacity(count);
    for _ in 0..count {
        let value = base * (0.85 + rng.next_f64() * 0.3);
        values.push(format!("{value:.0}"));
    }
    format!("[{}]", values.join(","))
}

async fn solve_captcha_via_webview(
    captcha: &CaptchaError,
    captcha_tx: broadcast::Sender<String>,
) -> Result<String> {
    let mut rx = captcha_tx.subscribe();
    println!(
        "CAPTCHA_SOLVE|{}|{}",
        captcha.redirect_uri, captcha.session_token
    );
    let _ = std::io::stdout().flush();
    let result = time::timeout(Duration::from_secs(300), rx.recv())
        .await
        .context("таймаут решения капчи через WV")?
        .context("канал капчи закрыт")?;
    if let Some(message) = result.strip_prefix("error:") {
        bail!("WV captcha error: {message}");
    }
    Ok(result)
}

fn extract_query_param(uri: &str, name: &str) -> String {
    let Some(query) = uri.split_once('?').map(|(_, query)| query) else {
        return String::new();
    };
    for part in query.split('&') {
        let Some((key, value)) = part.split_once('=') else {
            continue;
        };
        if key == name {
            return value.to_string();
        }
    }
    String::new()
}

fn value_to_string(value: Option<&Value>) -> String {
    match value {
        Some(Value::String(text)) => text.clone(),
        Some(Value::Number(number)) => number.to_string(),
        _ => String::new(),
    }
}

fn clean_turn_url(raw: &str) -> String {
    let clean = raw.split('?').next().unwrap_or(raw);
    clean
        .trim_start_matches("turn:")
        .trim_start_matches("turns:")
        .to_string()
}

fn override_turn_addr(raw: &str, params: &TurnParams) -> Result<String> {
    let mut host = raw.to_string();
    let mut port = "3478".to_string();
    if let Some((h, p)) = raw.rsplit_once(':') {
        host = h.trim_matches(['[', ']']).to_string();
        port = p.to_string();
    }
    if let Some(custom_host) = &params.host {
        host = custom_host.clone();
    }
    if let Some(custom_port) = &params.port {
        port = custom_port.clone();
    }
    if host.is_empty() || port.is_empty() {
        bail!("некорректный TURN адрес: {raw}");
    }
    Ok(format_turn_addr(&host, &port))
}

fn format_turn_addr(host: &str, port: &str) -> String {
    let clean_host = host.trim_matches(['[', ']']);
    if clean_host.contains(':') {
        format!("[{clean_host}]:{port}")
    } else {
        format!("{clean_host}:{port}")
    }
}

fn resolve_socket_addr(raw: &str) -> Result<SocketAddr> {
    raw.to_socket_addrs()?
        .next()
        .with_context(|| format!("не удалось резолвить {raw}"))
}

#[async_trait]
impl Conn for PeerConn {
    async fn connect(&self, _addr: SocketAddr) -> util::Result<()> {
        Ok(())
    }

    async fn recv(&self, buf: &mut [u8]) -> util::Result<usize> {
        loop {
            let (n, from) = self.relay.recv_from(buf).await?;
            if from == self.peer {
                return Ok(n);
            }
        }
    }

    async fn recv_from(&self, buf: &mut [u8]) -> util::Result<(usize, SocketAddr)> {
        let n = self.recv(buf).await?;
        Ok((n, self.peer))
    }

    async fn send(&self, buf: &[u8]) -> util::Result<usize> {
        self.relay.send_to(buf, self.peer).await
    }

    async fn send_to(&self, buf: &[u8], target: SocketAddr) -> util::Result<usize> {
        self.relay.send_to(buf, target).await
    }

    fn local_addr(&self) -> util::Result<SocketAddr> {
        self.relay.local_addr()
    }

    fn remote_addr(&self) -> Option<SocketAddr> {
        Some(self.peer)
    }

    async fn close(&self) -> util::Result<()> {
        self.relay.close().await
    }

    fn as_any(&self) -> &(dyn std::any::Any + Send + Sync) {
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_hashes_matches_go_core_cases() {
        let cases = [
            ("abc,def", vec!["abc", "def"]),
            ("https://vk.com/call/join/abc", vec!["abc"]),
            ("https://vk.ru/call/join/abc?foo=1#bar", vec!["abc"]),
            ("abc, https://vk.com/call/join/def", vec!["abc", "def"]),
            (" , \n\t,abc,, ", vec!["abc"]),
            ("vk.ru/call/join/abc", vec!["abc"]),
            (
                "friend sent https://vk.ru/call/join/abc in chat",
                vec!["abc"],
            ),
            (
                "links: https://vk.ru/call/join/abc and vk.com/call/join/def",
                vec!["abc", "def"],
            ),
        ];

        for (raw, want) in cases {
            assert_eq!(parse_hashes(raw), want);
        }
    }

    #[test]
    fn split_tunnel_rewrites_allowed_ips() {
        let config = "[Interface]\nPrivateKey = x\n\n[Peer]\nAllowedIPs = 0.0.0.0/0\n";
        let rewritten =
            modify_config_for_split_tunnel(config, IpAddr::V4(Ipv4Addr::new(203, 0, 113, 1)));
        assert!(rewritten.contains("AllowedIPs = "));
        assert!(!rewritten.contains("AllowedIPs = 0.0.0.0/0"));
    }

    #[test]
    fn no_dns_removes_dns_line() {
        let config = "[Interface]\nDNS = 77.88.8.8\nMTU = 1280\n";
        assert_eq!(remove_dns_from_config(config), "[Interface]\nMTU = 1280");
    }
}
