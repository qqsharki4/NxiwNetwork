package main

import (
	"context"
	"fmt"
	"log"
	"math/rand"
	"net"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode"
)

var groupAuthMutex sync.Mutex
var vkJoinLinkRE = regexp.MustCompile(`(?i)(?:https?://)?(?:[a-z0-9-]+\.)?vk\.(?:ru|com)/call/join/([^/?#\s,;]+)`)

const (
	workersPerGroup  = 12
	defaultCycleSecs = 36000
)

type runningGroupWorker struct {
	cancel context.CancelFunc
	done   chan struct{}
}

// WorkerGroup:
// бесшовная ротация: получить новые креды → запустить новый батч → убить старый.
func WorkerGroup(
	ctx context.Context,
	groupID int,
	hashIndex int,
	tp *TurnParams,
	peer *net.UDPAddr,
	d *Dispatcher,
	localPort string,
	useUDP bool,
	getConfig bool,
	configCh chan<- string,
	workerIDs []int,
	resizeCh <-chan []int,
	cycleDuration time.Duration,
	pauseFlag *int32,
	deviceID, password string,
	keepaliveInterval time.Duration,
	stats *Stats,
	waitReady <-chan struct{},
	signalReady chan<- struct{},
) {
	// Каскадный запуск: ждем свою очередь
	if waitReady != nil {
		log.Printf("[ГРУППА #%d] Ожидание сигнала от предыдущей группы...", groupID)
		select {
		case <-waitReady:
		case <-ctx.Done():
			return
		}
	}

	copyWorkerIDs := func(ids []int) []int {
		return append([]int(nil), ids...)
	}

	targetWorkerIDs := copyWorkerIDs(workerIDs)
	var desiredWorkerCount int32
	storeDesiredWorkerCount := func(ids []int) {
		count := len(ids)
		if count < 1 {
			count = 1
		}
		atomic.StoreInt32(&desiredWorkerCount, int32(count))
	}
	storeDesiredWorkerCount(targetWorkerIDs)

	drainResize := func() {
		for {
			select {
			case ids := <-resizeCh:
				if len(ids) == 0 {
					continue
				}
				targetWorkerIDs = copyWorkerIDs(ids)
				storeDesiredWorkerCount(targetWorkerIDs)
			default:
				return
			}
		}
	}

	workerThreshold := func(limit int) int {
		count := int(atomic.LoadInt32(&desiredWorkerCount))
		if count < 1 {
			count = 1
		}
		return minInt(limit, count)
	}

	cycleNumber := 0
	var configSent int32
	if !getConfig {
		configSent = 1
	}

	// Предыдущий батч
	var prevCancel context.CancelFunc
	var prevWorkers map[int]*runningGroupWorker
	var commonSignalOnce sync.Once

	killBatch := func() {
		if prevCancel != nil {
			prevCancel()
			for _, worker := range prevWorkers {
				select {
				case <-worker.done:
				case <-time.After(3 * time.Second):
				}
			}
			prevCancel = nil
			prevWorkers = nil
		}
	}
	defer killBatch()

	for {
		if ctx.Err() != nil {
			return
		}
		drainResize()

		// Doze-mode пауза: убиваем воркеров и ждём RESUME
		if atomic.LoadInt32(pauseFlag) != 0 {
			killBatch()
			log.Printf("[ГРУППА #%d] Пауза (Doze)", groupID)
			for {
				if ctx.Err() != nil {
					return
				}
				if atomic.LoadInt32(pauseFlag) == 0 {
					log.Printf("[ГРУППА #%d] Возобновление — новые креды", groupID)
					break
				}
				time.Sleep(1 * time.Second)
			}
		}

		// Получаем креды ДО убийства старого батча (бесшовная ротация)
		hash := tp.Hashes[hashIndex%len(tp.Hashes)]
		shortHash := hash
		if len(shortHash) > 8 {
			shortHash = shortHash[:8]
		}
		log.Printf("[ГРУППА #%d] Цикл %d: ожидание очереди получения кредов (хеш: %s...)", groupID, cycleNumber, shortHash)

		groupAuthMutex.Lock()
		log.Printf("[ГРУППА #%d] Цикл %d: запрос кредов", groupID, cycleNumber)
		creds, err := GetCredsWithFallback(ctx, tp, hash, stats)
		groupAuthMutex.Unlock()

		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("[ГРУППА #%d] Ошибка кредов: %v", groupID, err)
			select {
			case <-time.After(30 * time.Second):
			case <-ctx.Done():
				return
			}
			continue
		}

		drainResize()

		// Вычисляем точное время жизни на основе ответа VK (минус 2 минуты для надёжности)
		sleepDuration := defaultCycleSecs
		if creds.Lifetime > 120 {
			sleepDuration = creds.Lifetime - 120
		}
		cycleDurationLocal := time.Duration(sleepDuration) * time.Second

		groupWorkerCount := len(targetWorkerIDs)
		log.Printf("[ГРУППА #%d] Запуск %d потоков (до смены кредов: %d сек)", groupID, groupWorkerCount, sleepDuration)

		log.Printf("[ГРУППА #%d] Креды OK, TURN: %v, %d воркеров", groupID, creds.TurnURLs, len(targetWorkerIDs))

		// ТЕПЕРЬ убиваем старый батч (креды уже готовы — минимальный простой)
		killBatch()

		// Создаём новый batch
		batchCtx, batchCancel := context.WithCancel(ctx)
		var configRequestInFlight int32

		refreshCh := make(chan struct{}, 1)
		activeWorkers := make(map[int]*runningGroupWorker)
		var quotaErrorWorkers sync.Map
		var notFoundErrorWorkers sync.Map

		// Сигнализируем следующей группе, что мы успешно запустились (креды получены + 2 сек форы)
		go func() {
			commonSignalOnce.Do(func() {
				if signalReady != nil {
					time.Sleep(2000 * time.Millisecond) // Запас времени для рукопожатий (3*500ms + 500ms)
					close(signalReady)
					log.Printf("[ГРУППА #%d] Успешный старт! Передача эстафеты следующей группе...", groupID)
				}
			})
		}()

		stopWorker := func(worker *runningGroupWorker) {
			worker.cancel()
			go func(done <-chan struct{}) {
				select {
				case <-done:
				case <-time.After(3 * time.Second):
				}
			}(worker.done)
		}

		launchWorker := func(wid int, delay time.Duration) {
			doneCh := make(chan struct{})
			workerCtx, workerCancel := context.WithCancel(batchCtx)
			activeWorkers[wid] = &runningGroupWorker{cancel: workerCancel, done: doneCh}

			go func(wid int, delay time.Duration, doneCh chan struct{}) {
				defer close(doneCh)

				if delay > 0 {
					select {
					case <-time.After(delay):
					case <-workerCtx.Done():
						return
					}
				}

				shouldGetConfig := getConfig

				// Retry loop: воркер переподключается при ошибке
				attempt := 0
				for {
					if workerCtx.Err() != nil {
						return
					}

					getConf := false
					if shouldGetConfig && attempt == 0 && atomic.LoadInt32(&configSent) == 0 {
						getConf = atomic.CompareAndSwapInt32(&configRequestInFlight, 0, 1)
					}
					var cc chan<- string
					if getConf {
						cc = configCh
					}

					configDelivered, sessErr := RunSession(workerCtx, tp, peer, d, localPort, useUDP,
						getConf, cc, wid, creds, deviceID, password, keepaliveInterval, stats)

					if getConf {
						if configDelivered {
							atomic.StoreInt32(&configSent, 1)
						} else {
							atomic.StoreInt32(&configRequestInFlight, 0)
						}
					}

					if sessErr != nil {
						if workerCtx.Err() != nil {
							return
						}
						errStr := sessErr.Error()

						// Дописываем понятные пояснения для типичных ошибок со стороны балансировщиков ВК
						errStrLower := strings.ToLower(errStr)
						if strings.Contains(errStrLower, "attribute not found") ||
							strings.Contains(errStrLower, "rate limit") ||
							strings.Contains(errStrLower, "flood control") ||
							strings.Contains(errStrLower, "ip mismatch") ||
							strings.Contains(errStrLower, "error 29") {
							errStr += " (ошибка со стороны ВК)"
						}

						// Фатальные ошибки — смерть аккаунта
						if strings.Contains(errStr, "хеш мёртв") ||
							strings.Contains(errStr, "FATAL_AUTH") {
							log.Printf("[ВОРКЕР #%d] Фатальная ошибка: %s", wid, errStr)
							return
						}

						// Исчерпана ли квота TURN?
						if strings.Contains(errStrLower, "turn квота") || strings.Contains(errStrLower, "quota") {
							quotaErrorWorkers.Store(wid, true)
							qCount := 0
							quotaErrorWorkers.Range(func(k, v any) bool { qCount++; return true })
							if qCount >= workerThreshold(5) {
								select {
								case refreshCh <- struct{}{}:
									log.Printf("[ГРУППА #%d] Досрочная ротация: исчерпана квота TURN у %d воркеров", groupID, qCount)
								default:
								}
							}
							log.Printf("[ВОРКЕР #%d] Ошибка квоты TURN: %s", wid, errStr)
							return // Воркер завершается, на текущих кредах он больше не поднимется
						}

						attempt++
						log.Printf("[ВОРКЕР #%d] Ошибка (попытка %d): %s", wid, attempt, errStr)

						// Умерли ли креды? (Строго STUN/TURN ошибки: интернет работает, но сервер отвергает ключи)
						isStunDeath := strings.Contains(errStrLower, "attribute not found") ||
							strings.Contains(errStrLower, "error 29") ||
							strings.Contains(errStrLower, "unauthorized") ||
							strings.Contains(errStrLower, "allocation mismatch") ||
							strings.Contains(errStrLower, "error 508") ||
							strings.Contains(errStrLower, "cannot create socket")

						isStreamClosed := strings.Contains(errStrLower, "stream closed")

						if isStreamClosed {
							select {
							case refreshCh <- struct{}{}:
								log.Printf("[ГРУППА #%d] Мгновенная ротация: сервер ВК закрыл поток (Stream Closed)", groupID)
							default:
							}
						} else if isStunDeath {
							notFoundErrorWorkers.Store(wid, true)
							nfCount := 0
							notFoundErrorWorkers.Range(func(k, v any) bool { nfCount++; return true })

							// Если большинство воркеров получили явный отказ от сервера — ключи протухли.
							if nfCount >= workerThreshold(8) {
								select {
								case refreshCh <- struct{}{}:
									log.Printf("[ГРУППА #%d] Досрочная ротация: сервер ВК убил сессию (у %d воркеров)", groupID, nfCount)
								default:
								}
							}
						}
					}

					if workerCtx.Err() != nil {
						return
					}

					// Пауза перед ретраем с джиттером 5-15 сек
					retryDelay := time.Duration(5+rand.Intn(11)) * time.Second
					select {
					case <-time.After(retryDelay):
					case <-workerCtx.Done():
						return
					}
				}
			}(wid, delay, doneCh)
		}

		reconcileWorkers := func(ids []int) {
			desired := make(map[int]struct{}, len(ids))
			for _, wid := range ids {
				desired[wid] = struct{}{}
			}
			for wid, worker := range activeWorkers {
				if _, ok := desired[wid]; ok {
					continue
				}
				delete(activeWorkers, wid)
				stopWorker(worker)
			}
			addIndex := 0
			for _, wid := range ids {
				if _, ok := activeWorkers[wid]; ok {
					continue
				}
				launchWorker(wid, time.Duration(addIndex)*500*time.Millisecond)
				addIndex++
			}
		}

		reconcileWorkers(targetWorkerIDs)

		// Сохраняем батч для бесшовной ротации
		prevCancel = batchCancel
		prevWorkers = activeWorkers

		// Ждём TTL, досрочной ротации или точечного изменения состава воркеров.
		ttlTimer := time.NewTimer(cycleDurationLocal)
		rotate := false
		for !rotate {
			select {
			case <-ttlTimer.C:
				log.Printf("[ГРУППА #%d] TTL %v истёк, ротация", groupID, cycleDurationLocal)
				rotate = true
			case <-refreshCh:
				log.Printf("[ГРУППА #%d] Вызвана досрочная ротация (креды не отвечали)", groupID)
				rotate = true
			case ids := <-resizeCh:
				if len(ids) > 0 {
					targetWorkerIDs = copyWorkerIDs(ids)
					storeDesiredWorkerCount(targetWorkerIDs)
					reconcileWorkers(targetWorkerIDs)
				}
			case <-ctx.Done():
				if !ttlTimer.Stop() {
					select {
					case <-ttlTimer.C:
					default:
					}
				}
				return
			}
		}
		if !ttlTimer.Stop() {
			select {
			case <-ttlTimer.C:
			default:
			}
		}

		cycleNumber++
	}
}

func normalizeVKHash(raw string) string {
	h := strings.Trim(strings.TrimSpace(raw), ",;")
	if match := vkJoinLinkRE.FindStringSubmatch(h); len(match) > 1 {
		return strings.TrimSuffix(strings.TrimSpace(match[1]), "/")
	}
	if idx := strings.IndexAny(h, "/?#"); idx != -1 {
		h = h[:idx]
	}
	return h
}

// ParseHashes — парсит строку хешей
func ParseHashes(raw string) []string {
	var result []string
	for _, chunk := range strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == '\n' || r == '\r'
	}) {
		matches := vkJoinLinkRE.FindAllStringSubmatch(chunk, -1)
		if len(matches) > 0 {
			for _, match := range matches {
				if len(match) > 1 {
					if h := normalizeVKHash(match[1]); h != "" {
						result = append(result, h)
					}
				}
			}
			continue
		}

		for _, h := range strings.FieldsFunc(chunk, unicode.IsSpace) {
			h = normalizeVKHash(h)
			if h != "" {
				result = append(result, h)
			}
		}
	}
	return result
}

// TurnParams — конфигурация TURN
type TurnParams struct {
	Host          string
	Port          string
	Hashes        []string
	SecondaryHash string
	Sni           string
	Dns           string
	Mtu           int
	WrapKey       []byte
}

// Unused import suppressor
var _ = fmt.Sprintf
