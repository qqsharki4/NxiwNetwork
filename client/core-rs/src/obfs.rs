use anyhow::{bail, Result};
use getrandom::getrandom;
use hkdf::Hkdf;
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, CHACHA20_POLY1305};
use sha2::Sha256;
use std::sync::Mutex;

const WRAP_KEY_LEN: usize = 32;
const RTP_HEADER_LEN: usize = 12;
const TAG_LEN: usize = 16;
const RTP_PAYLOAD_TYPE: u8 = 111;
const DEFAULT_PADDING_MAX: usize = 24;
const RNG_FALLBACK: u64 = 0x9e3779b97f4a7c15;

pub fn derive_wrap_key(password: &str) -> Result<[u8; WRAP_KEY_LEN]> {
    if password.is_empty() {
        bail!("empty password");
    }

    let hk = Hkdf::<Sha256>::new(Some(b"WDTT-WRAP-v1"), password.as_bytes());
    let mut key = [0u8; WRAP_KEY_LEN];
    hk.expand(b"rtp-obfs/chacha20poly1305", &mut key)
        .map_err(|_| anyhow::anyhow!("derive wrap key"))?;
    Ok(key)
}

pub struct ObfsCodec {
    key: LessSafeKey,
    cfg: ObfsConfig,
    write_state: Mutex<ObfsState>,
}

impl ObfsCodec {
    pub fn new(key: [u8; WRAP_KEY_LEN]) -> Result<Self> {
        let unbound = UnboundKey::new(&CHACHA20_POLY1305, &key)
            .map_err(|_| anyhow::anyhow!("obfs: cipher init"))?;
        Ok(Self {
            key: LessSafeKey::new(unbound),
            cfg: ObfsConfig::new(),
            write_state: Mutex::new(ObfsState::new()),
        })
    }

    pub fn max_wire_len(payload_len: usize) -> usize {
        RTP_HEADER_LEN + payload_len + TAG_LEN + DEFAULT_PADDING_MAX
    }

    pub fn wrap_packet_to(&self, payload: &[u8], out: &mut Vec<u8>) -> Result<usize> {
        if payload.is_empty() {
            bail!("obfs: empty payload");
        }

        let (seq, ts, pad_rand, pad_seed) = {
            let mut state = self
                .write_state
                .lock()
                .map_err(|_| anyhow::anyhow!("obfs: write state poisoned"))?;
            let seq = state.seq;
            let ts = state.ts;
            state.seq = state.seq.wrapping_add(1);
            state.ts = state.ts.wrapping_add(960);

            let mut pad_rand = 0usize;
            let mut pad_seed = state.rng;
            if self.cfg.padding_max > 0 {
                state.rng = obfs_next_rand(state.rng);
                pad_seed = state.rng;
                pad_rand = usize::from(pad_seed as u8) % self.cfg.padding_max;
            }
            (seq, ts, pad_rand, pad_seed)
        };

        let pad_total = pad_rand + 1;
        let out_len = RTP_HEADER_LEN + payload.len() + TAG_LEN + pad_total;
        out.clear();
        out.reserve(out_len);

        let mut header = [0u8; RTP_HEADER_LEN];
        header[0] = 0x80 | 0x20;
        header[1] = self.cfg.payload_type & 0x7F;
        header[2..4].copy_from_slice(&seq.to_be_bytes());
        header[4..8].copy_from_slice(&ts.to_be_bytes());
        header[8..12].copy_from_slice(&self.cfg.ssrc.to_be_bytes());
        out.extend_from_slice(&header);
        out.extend_from_slice(payload);

        let nonce = build_nonce(self.cfg.ssrc, seq, ts)?;
        let aad = Aad::from(&header);
        let tag = self
            .key
            .seal_in_place_separate_tag(nonce, aad, &mut out[RTP_HEADER_LEN..])
            .map_err(|_| anyhow::anyhow!("obfs: seal"))?;
        out.extend_from_slice(tag.as_ref());

        if pad_rand > 0 {
            let start = out.len();
            out.resize(start + pad_rand, 0);
            obfs_fill_padding(&mut out[start..], pad_seed);
        }
        out.push(pad_total as u8);
        Ok(out.len())
    }

    pub fn unwrap_packet_in_place(&self, wire: &mut [u8]) -> Result<usize> {
        if wire.len() < RTP_HEADER_LEN + 1 {
            bail!("obfs: packet too short");
        }
        if !is_rtp_packet(wire) {
            bail!("obfs: not RTP payload");
        }

        let seq = u16::from_be_bytes([wire[2], wire[3]]);
        let ts = u32::from_be_bytes([wire[4], wire[5], wire[6], wire[7]]);
        let ssrc = u32::from_be_bytes([wire[8], wire[9], wire[10], wire[11]]);

        let mut payload_end = wire.len();
        if wire[0] & 0x20 != 0 {
            let pad_len = usize::from(wire[wire.len() - 1]);
            if pad_len == 0 || pad_len > payload_end - RTP_HEADER_LEN {
                bail!("obfs: invalid padding length {pad_len}");
            }
            payload_end -= pad_len;
        }

        let ciphertext_len = payload_end.saturating_sub(RTP_HEADER_LEN);
        if ciphertext_len <= TAG_LEN {
            bail!("obfs: no payload");
        }

        let nonce = build_nonce(ssrc, seq, ts)?;
        let mut header = [0u8; RTP_HEADER_LEN];
        header.copy_from_slice(&wire[..RTP_HEADER_LEN]);
        let aad = Aad::from(&header);
        let plain_len = {
            let plain = self
                .key
                .open_in_place(nonce, aad, &mut wire[RTP_HEADER_LEN..payload_end])
                .map_err(|_| anyhow::anyhow!("obfs: auth"))?;
            plain.len()
        };
        wire.copy_within(RTP_HEADER_LEN..RTP_HEADER_LEN + plain_len, 0);
        Ok(plain_len)
    }
}

struct ObfsConfig {
    ssrc: u32,
    payload_type: u8,
    padding_max: usize,
}

impl ObfsConfig {
    fn new() -> Self {
        let mut buf = [0u8; 4];
        let _ = getrandom(&mut buf);
        Self {
            ssrc: u32::from_be_bytes(buf),
            payload_type: RTP_PAYLOAD_TYPE,
            padding_max: DEFAULT_PADDING_MAX,
        }
    }
}

struct ObfsState {
    seq: u16,
    ts: u32,
    rng: u64,
}

impl ObfsState {
    fn new() -> Self {
        let mut buf = [0u8; 14];
        let _ = getrandom(&mut buf);
        let mut rng = u64::from_be_bytes(buf[6..14].try_into().unwrap_or([0u8; 8]));
        if rng == 0 {
            rng = RNG_FALLBACK;
        }
        Self {
            seq: u16::from_be_bytes([buf[0], buf[1]]),
            ts: u32::from_be_bytes([buf[2], buf[3], buf[4], buf[5]]),
            rng,
        }
    }
}

fn build_nonce(ssrc: u32, seq: u16, ts: u32) -> Result<Nonce> {
    let mut nonce = [0u8; 12];
    nonce[0..4].copy_from_slice(&ssrc.to_be_bytes());
    nonce[4..6].copy_from_slice(&seq.to_be_bytes());
    nonce[8..12].copy_from_slice(&ts.to_be_bytes());
    Nonce::try_assume_unique_for_key(&nonce).map_err(|_| anyhow::anyhow!("obfs: nonce"))
}

fn obfs_next_rand(mut seed: u64) -> u64 {
    seed ^= seed << 7;
    seed ^= seed >> 9;
    seed ^= seed << 8;
    if seed == 0 {
        RNG_FALLBACK
    } else {
        seed
    }
}

fn obfs_fill_padding(dst: &mut [u8], mut seed: u64) {
    for byte in dst {
        seed = obfs_next_rand(seed);
        *byte = seed as u8;
    }
}

fn is_rtp_packet(wire: &[u8]) -> bool {
    wire.len() >= RTP_HEADER_LEN + 1 && (wire[0] >> 6) == 2 && wire[1] & 0x7F == RTP_PAYLOAD_TYPE
}
