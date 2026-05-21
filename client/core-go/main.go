package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

var (
	vkAppID     atomic.Value // string
	vkAppSecret atomic.Value // string
	captchaMode atomic.Value // string — "reverse_js" или "webview"
)

// CaptchaSolver — канал для получения токена капчи из внешнего решателя (WebView)
// Формат: "token:<success_token>" или "error:<message>"
var CaptchaResultCh = make(chan string, 1)

const maxRuntimeWorkers = 72

// drainCaptchaResult удаляет устаревший результат капчи из канала (если остался)
func drainCaptchaResult() {
	select {
	case <-CaptchaResultCh:
	default:
	}
}

func init() {
	vkAppID.Store("6287487")
	vkAppSecret.Store("QbYic1K3lEV5kTGiqlq2")
}

func parseWorkerResizeCommand(line string) (int, bool) {
	fields := strings.FieldsFunc(strings.TrimSpace(line), func(r rune) bool {
		return r == '|' || r == '=' || r == ':' || r == ' ' || r == '\t'
	})
	if len(fields) < 2 {
		return 0, false
	}
	cmd := strings.ToUpper(strings.TrimSpace(fields[0]))
	switch cmd {
	case "SET_WORKERS", "WORKERS", "WORKER_COUNT", "RESIZE_WORKERS":
	default:
		return 0, false
	}
	var value int
	if _, err := fmt.Sscanf(fields[1], "%d", &value); err != nil {
		return 0, false
	}
	if value < 1 {
		value = 1
	}
	if value > maxRuntimeWorkers {
		value = maxRuntimeWorkers
	}
	return value, true
}

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Сигналы
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		select {
		case s := <-sig:
			log.Printf("[КЛИЕНТ] Сигнал %v, завершаю...", s)
			cancel()
		case <-ctx.Done():
			return
		}
		select {
		case s := <-sig:
			log.Printf("[КЛИЕНТ] Повторный %v, принудительный выход", s)
			os.Exit(1)
		case <-ctx.Done():
		}
	}()

	var pauseFlag int32 // 0 = активен, 1 = пауза (Doze-mode)
	resizeWorkersCh := make(chan int, 4)

	// STDIN для PAUSE/RESUME/STOP (Doze-mode) и CAPTCHA_RESULT (WebView mode)
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if !strings.Contains(line, "error:tunnel stopped") {
				log.Printf("[STDIN] %s", line)
			}
			switch {
			case line == "PAUSE":
				atomic.StoreInt32(&pauseFlag, 1)
			case line == "RESUME":
				atomic.StoreInt32(&pauseFlag, 0)
			case line == "STOP":
				cancel()
				return
			case strings.HasPrefix(line, "CAPTCHA_RESULT|"):
				// Формат: CAPTCHA_RESULT|token или CAPTCHA_RESULT|error:msg
				result := strings.TrimPrefix(line, "CAPTCHA_RESULT|")
				// Дренируем старый результат, если он не был прочитан
				drainCaptchaResult()
				// Гарантированная запись нового результата
				CaptchaResultCh <- result
				log.Printf("[КАПЧА] Результат от Kotlin записан в канал")
			default:
				if workers, ok := parseWorkerResizeCommand(line); ok {
					select {
					case resizeWorkersCh <- workers:
						log.Printf("[CONTROL] Запрошено изменение воркеров: %d", workers)
					default:
						log.Printf("[CONTROL] Пропускаю SET_WORKERS=%d: предыдущая команда ещё обрабатывается", workers)
					}
				}
			}
		}
	}()

	host := flag.String("turn", "", "переопределить IP TURN")
	port := flag.String("port", "", "переопределить порт TURN")
	listen := flag.String("listen", "127.0.0.1:9000", "локальный адрес")
	vkHash := flag.String("vk", "", "хеши VK-звонков (через запятую)")
	secondaryHash := flag.String("vk2", "", "запасной VK хеш")
	peerAddr := flag.String("peer", "", "адрес:порт VPS сервера")
	numW := flag.Int("n", 24, "количество воркеров (кратно 12)")
	useTCP := flag.Bool("tcp", false, "TURN через TCP")
	useUDP := flag.Bool("udp", false, "TURN через UDP")
	splitTunnel := flag.Bool("split", false, "split tunneling")
	sni := flag.String("sni", "", "SNI для DTLS")
	dnsOverride := flag.String("dns", "", "DNS для WireGuard-конфига")
	noDns := flag.Bool("nodns", false, "отключить DNS Яндекса")

	appID := flag.String("vk-app-id", "6287487", "VK App ID")
	appSecret := flag.String("vk-app-secret", "QbYic1K3lEV5kTGiqlq2", "VK App Secret")

	deviceID := flag.String("device-id", "unknown", "уникальный ID устройства")
	userAgent := flag.String("user-agent", "", "User-Agent строка устройства")
	connPassword := flag.String("password", "", "пароль подключения")
	captchaModeFlag := flag.String("captcha-mode", "rjs", "режим капчи: wv, rjs или rjs_slider")
	keepaliveSeconds := flag.Int("keepalive-sec", 10, "интервал keepalive клиента в секундах (5-60)")

	flag.Parse()

	vkAppID.Store(*appID)
	vkAppSecret.Store(*appSecret)
	captchaMode.Store(*captchaModeFlag)
	SetCaptchaModeEnv(*captchaModeFlag)
	noDnsFlag.Store(*noDns)
	SetUserAgent(*userAgent)

	if *peerAddr == "" || *vkHash == "" {
		log.Fatal("[КЛИЕНТ] Нужны -peer и -vk")
	}

	peer, err := net.ResolveUDPAddr("udp", *peerAddr)
	if err != nil {
		log.Fatalf("[КЛИЕНТ] Ошибка разбора пира: %v", err)
	}

	hashes := ParseHashes(*vkHash)
	if len(hashes) == 0 {
		log.Fatal("[КЛИЕНТ] Нет хешей VK")
	}

	// Протокол по умолчанию
	if !*useTCP && !*useUDP {
		*useTCP = true
	}

	// Лимит воркеров
	if *numW > maxRuntimeWorkers {
		*numW = maxRuntimeWorkers
	}
	if *numW < 1 {
		*numW = 1
	}
	if *keepaliveSeconds < 5 {
		*keepaliveSeconds = 5
	}
	if *keepaliveSeconds > 60 {
		*keepaliveSeconds = 60
	}
	keepaliveInterval := time.Duration(*keepaliveSeconds) * time.Second

	tp := &TurnParams{
		Host:          *host,
		Port:          *port,
		Hashes:        hashes,
		SecondaryHash: strings.TrimSpace(*secondaryHash),
		Sni:           *sni,
		Dns:           strings.TrimSpace(*dnsOverride),
	}

	// Слушаем локально
	localConn, err := net.ListenPacket("udp", *listen)
	if err != nil {
		log.Fatalf("[КЛИЕНТ] Ошибка слушателя %s: %v", *listen, err)
	}
	if uc, ok := localConn.(*net.UDPConn); ok {
		_ = uc.SetReadBuffer(socketBufSize)
		_ = uc.SetWriteBuffer(socketBufSize)
	}
	stopLocalConn := context.AfterFunc(ctx, func() { _ = localConn.Close() })
	defer stopLocalConn()

	_, localPort, _ := net.SplitHostPort(*listen)
	if localPort == "" {
		localPort = "9000"
	}

	numGroups := groupCountForWorkers(*numW)

	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")
	log.Printf("[КЛИЕНТ] VK App: %s", *appID)
	log.Printf("[КЛИЕНТ] Воркеров: %d (групп: %d, до %d в группе)", *numW, numGroups, workersPerGroup)
	log.Printf("[КЛИЕНТ] Хешей: %d", len(hashes))
	log.Printf("[КЛИЕНТ] Слушаю: %s | Пир: %s", *listen, *peerAddr)
	proto := "TCP"
	if *useUDP {
		proto = "UDP"
	}
	log.Printf("[КЛИЕНТ] Протокол: %s", proto)
	log.Printf("[КЛИЕНТ] Device ID: %s", *deviceID)
	log.Printf("[КЛИЕНТ] Обход капчи: %s", captchaMode.Load().(string))
	log.Printf("[КЛИЕНТ] Keepalive: %d сек", *keepaliveSeconds)
	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")

	stats := NewStats()
	shutdownCh := make(chan struct{})
	go func() {
		<-ctx.Done()
		close(shutdownCh)
	}()
	go stats.RunLoop(shutdownCh)

	disp := NewDispatcher(ctx, localConn, stats)
	defer disp.Shutdown()

	configCh := make(chan string, 1)
	configDone := make(chan struct{})
	go func() {
		defer close(configDone)
		select {
		case rawConf, ok := <-configCh:
			if !ok || rawConf == "" {
				return
			}
			finalConf := rawConf
			if !strings.Contains(finalConf, "MTU =") {
				lines := strings.Split(finalConf, "\n")
				var newLines []string
				for _, line := range lines {
					newLines = append(newLines, line)
					if strings.TrimSpace(line) == "[Interface]" {
						newLines = append(newLines, "MTU = 1280")
					}
				}
				finalConf = strings.Join(newLines, "\n")
			}
			if *splitTunnel {
				finalConf = ModifyConfigForSplitTunnel(finalConf, peer.IP)
			}
			fmt.Println()
			fmt.Println("╔══════════════ WireGuard Конфиг ══════════════╗")
			for _, line := range strings.Split(finalConf, "\n") {
				fmt.Printf("║ %-44s ║\n", line)
			}
			fmt.Println("╚══════════════════════════════════════════════╝")
			if err := os.WriteFile("wg-turn.conf", []byte(finalConf+"\n"), 0600); err != nil {
				log.Printf("[КОНФИГ] Ошибка сохранения: %v", err)
			} else {
				log.Println("[КОНФИГ] Сохранён в wg-turn.conf")
			}
		case <-ctx.Done():
		}
	}()

	supervisor := workerSupervisor{
		tp:                tp,
		peer:              peer,
		dispatcher:        disp,
		localPort:         localPort,
		useUDP:            *useUDP,
		configCh:          configCh,
		pauseFlag:         &pauseFlag,
		deviceID:          *deviceID,
		password:          *connPassword,
		keepaliveInterval: keepaliveInterval,
		stats:             stats,
	}
	supervisor.Run(ctx, *numW, resizeWorkersCh)
	<-configDone
	log.Println("[КЛИЕНТ] Все воркеры завершены")
}

type workerSupervisor struct {
	tp                *TurnParams
	peer              *net.UDPAddr
	dispatcher        *Dispatcher
	localPort         string
	useUDP            bool
	configCh          chan<- string
	pauseFlag         *int32
	deviceID          string
	password          string
	keepaliveInterval time.Duration
	stats             *Stats
	nextWorkerID      int
}

type workerGroupHandle struct {
	groupID   int
	size      int
	workerIDs []int
	resizeCh  chan []int
	cancel    context.CancelFunc
	done      chan struct{}
}

func groupCountForWorkers(workers int) int {
	if workers < 1 {
		workers = 1
	}
	return (workers + workersPerGroup - 1) / workersPerGroup
}

func groupSizeForWorkers(totalWorkers, groupID int) int {
	if totalWorkers < 1 || groupID < 1 {
		return 0
	}
	start := (groupID - 1) * workersPerGroup
	if start >= totalWorkers {
		return 0
	}
	size := totalWorkers - start
	if size > workersPerGroup {
		size = workersPerGroup
	}
	return size
}

func (s *workerSupervisor) nextWorkerIDs(count int) []int {
	if s.nextWorkerID <= 0 {
		s.nextWorkerID = 1
	}
	ids := make([]int, count)
	for i := range ids {
		ids[i] = s.nextWorkerID
		s.nextWorkerID++
	}
	return ids
}

func (s *workerSupervisor) Run(ctx context.Context, initialWorkers int, resizeCh <-chan int) {
	handles := make(map[int]*workerGroupHandle)
	currentWorkers := initialWorkers
	s.reconcile(ctx, handles, currentWorkers)
	defer s.stopAll(handles)

	for {
		select {
		case <-ctx.Done():
			return
		case requested := <-resizeCh:
			if requested < 1 {
				requested = 1
			}
			if requested > maxRuntimeWorkers {
				requested = maxRuntimeWorkers
			}
			if requested == currentWorkers {
				log.Printf("[CONTROL] Воркеров уже %d, изменений нет", requested)
				continue
			}
			log.Printf("[CONTROL] Runtime resize воркеров: %d → %d", currentWorkers, requested)
			currentWorkers = requested
			s.reconcile(ctx, handles, currentWorkers)
		}
	}
}

func (s *workerSupervisor) reconcile(ctx context.Context, handles map[int]*workerGroupHandle, targetWorkers int) {
	targetGroups := groupCountForWorkers(targetWorkers)
	for groupID, handle := range handles {
		if groupID > targetGroups {
			log.Printf("[CONTROL] Останавливаю группу #%d (%d воркеров)", groupID, handle.size)
			s.stopGroup(handle)
			delete(handles, groupID)
		}
	}
	for groupID := 1; groupID <= targetGroups; groupID++ {
		targetSize := groupSizeForWorkers(targetWorkers, groupID)
		if targetSize <= 0 {
			continue
		}
		if handle, ok := handles[groupID]; ok {
			if handle.size == targetSize {
				continue
			}
			workerIDs := append([]int(nil), handle.workerIDs...)
			if len(workerIDs) < targetSize {
				workerIDs = append(workerIDs, s.nextWorkerIDs(targetSize-len(workerIDs))...)
			} else {
				workerIDs = workerIDs[:targetSize]
			}
			handle.size = targetSize
			handle.workerIDs = append([]int(nil), workerIDs...)
			log.Printf("[CONTROL] Изменяю группу #%d: %d воркеров", groupID, targetSize)
			s.sendGroupResize(handle, workerIDs)
			continue
		}
		handles[groupID] = s.startGroup(ctx, groupID, targetSize)
	}
}

func (s *workerSupervisor) startGroup(ctx context.Context, groupID, size int) *workerGroupHandle {
	groupCtx, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	workerIDs := s.nextWorkerIDs(size)
	resizeCh := make(chan []int, 1)
	getConfig := groupID == 1
	var configCh chan<- string
	if getConfig {
		configCh = s.configCh
	}
	log.Printf("[CONTROL] Запускаю группу #%d: %d воркеров", groupID, size)
	go func() {
		defer close(done)
		WorkerGroup(groupCtx, groupID, groupID-1, s.tp, s.peer, s.dispatcher, s.localPort, s.useUDP,
			getConfig, configCh, workerIDs, resizeCh, time.Duration(defaultCycleSecs)*time.Second, s.pauseFlag,
			s.deviceID, s.password, s.keepaliveInterval, s.stats, nil, nil)
	}()
	return &workerGroupHandle{
		groupID:   groupID,
		size:      size,
		workerIDs: append([]int(nil), workerIDs...),
		resizeCh:  resizeCh,
		cancel:    cancel,
		done:      done,
	}
}

func (s *workerSupervisor) sendGroupResize(handle *workerGroupHandle, workerIDs []int) {
	payload := append([]int(nil), workerIDs...)
	select {
	case handle.resizeCh <- payload:
	default:
		select {
		case <-handle.resizeCh:
		default:
		}
		handle.resizeCh <- payload
	}
}

func (s *workerSupervisor) stopGroup(handle *workerGroupHandle) {
	handle.cancel()
	select {
	case <-handle.done:
	case <-time.After(10 * time.Second):
		log.Printf("[CONTROL] Группа #%d ещё завершается, продолжаю без ожидания", handle.groupID)
	}
}

func (s *workerSupervisor) stopAll(handles map[int]*workerGroupHandle) {
	for groupID, handle := range handles {
		log.Printf("[CONTROL] Останавливаю группу #%d", groupID)
		s.stopGroup(handle)
		delete(handles, groupID)
	}
}
