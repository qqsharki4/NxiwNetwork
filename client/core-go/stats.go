package main

import (
	"log"
	"sync/atomic"
	"time"
)

type Stats struct {
	ActiveConnections int32
	Reconnects        int64
	TotalBytesUp      int64
	TotalBytesDown    int64
	PacketsUp         int64
	PacketsDown       int64
	DroppedPackets    int64
	CredsErrors       int64
}

func NewStats() *Stats {
	return &Stats{}
}

func (s *Stats) RunLoop(shutdown <-chan struct{}) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	lastTime := time.Now()
	lastUp := int64(0)
	lastDown := int64(0)
	lastPacketsUp := int64(0)
	lastPacketsDown := int64(0)
	ticks := 0

	for {
		select {
		case <-shutdown:
			return
		case <-ticker.C:
			now := time.Now()
			elapsedSeconds := now.Sub(lastTime).Seconds()
			if elapsedSeconds <= 0 {
				elapsedSeconds = 1
			}

			active := atomic.LoadInt32(&s.ActiveConnections)
			up := atomic.LoadInt64(&s.TotalBytesUp)
			down := atomic.LoadInt64(&s.TotalBytesDown)
			packetsUp := atomic.LoadInt64(&s.PacketsUp)
			packetsDown := atomic.LoadInt64(&s.PacketsDown)
			dropped := atomic.LoadInt64(&s.DroppedPackets)
			upBps := int64(float64(up-lastUp) / elapsedSeconds)
			downBps := int64(float64(down-lastDown) / elapsedSeconds)
			upPps := int64(float64(packetsUp-lastPacketsUp) / elapsedSeconds)
			downPps := int64(float64(packetsDown-lastPacketsDown) / elapsedSeconds)
			if upBps < 0 {
				upBps = 0
			}
			if downBps < 0 {
				downBps = 0
			}
			if upPps < 0 {
				upPps = 0
			}
			if downPps < 0 {
				downPps = 0
			}

			log.Printf(
				"[CORE_METRICS] active=%d total_up=%d total_down=%d up_bps=%d down_bps=%d packets_up=%d packets_down=%d up_pps=%d down_pps=%d drops=%d",
				active,
				up,
				down,
				upBps,
				downBps,
				packetsUp,
				packetsDown,
				upPps,
				downPps,
				dropped,
			)

			totalMB := float64(up+down) / (1024.0 * 1024.0)
			upMB := float64(up) / (1024.0 * 1024.0)
			downMB := float64(down) / (1024.0 * 1024.0)

			ticks++
			if ticks%3 == 0 {
				log.Printf(
					"[СТАТИСТИКА] Активных: %d | Всего: %.2f МБ | ↑ %.2f МБ / %d пак | ↓ %.2f МБ / %d пак | Дропы: %d",
					active,
					totalMB,
					upMB,
					packetsUp,
					downMB,
					packetsDown,
					dropped,
				)
			}

			lastTime = now
			lastUp = up
			lastDown = down
			lastPacketsUp = packetsUp
			lastPacketsDown = packetsDown
		}
	}
}
