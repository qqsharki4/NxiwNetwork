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
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-shutdown:
			return
		case <-ticker.C:
			active := atomic.LoadInt32(&s.ActiveConnections)
			up := atomic.LoadInt64(&s.TotalBytesUp)
			down := atomic.LoadInt64(&s.TotalBytesDown)
			packetsUp := atomic.LoadInt64(&s.PacketsUp)
			packetsDown := atomic.LoadInt64(&s.PacketsDown)
			dropped := atomic.LoadInt64(&s.DroppedPackets)
			totalMB := float64(up+down) / (1024.0 * 1024.0)
			upMB := float64(up) / (1024.0 * 1024.0)
			downMB := float64(down) / (1024.0 * 1024.0)

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
	}
}
