package main

import (
	"context"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	returnChBuf            = 384
	schedulerProbeWindow   = 8
	schedulerMaxScanWindow = 256
)

type WorkerSlot struct {
	ID                int
	SendCh            chan []byte
	QueuedPackets     int64
	QueuedBytes       int64
	WrittenPackets    int64
	WrittenBytes      int64
	ReturnedPackets   int64
	ReturnedBytes     int64
	LastWriteUnixNano int64
}

type Dispatcher struct {
	localConn  net.PacketConn
	clientAddr atomic.Pointer[net.Addr]
	mu         sync.Mutex
	workers    []*WorkerSlot
	rrIndex    int
	ReturnCh   chan []byte
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	stats      *Stats
}

func NewDispatcher(ctx context.Context, localConn net.PacketConn, stats *Stats) *Dispatcher {
	dctx, dcancel := context.WithCancel(ctx)
	d := &Dispatcher{
		localConn: localConn,
		ReturnCh:  make(chan []byte, returnChBuf),
		ctx:       dctx,
		cancel:    dcancel,
		stats:     stats,
	}

	d.wg.Add(2)
	go d.readLoop()
	go d.writeLoop()
	return d
}

func (d *Dispatcher) Shutdown() {
	d.cancel()
	d.wg.Wait()
}

func (d *Dispatcher) Register(w *WorkerSlot) {
	d.mu.Lock()
	d.workers = append(d.workers, w)
	count := len(d.workers)
	d.mu.Unlock()
	log.Printf("[ДИСП] Воркер #%d зарегистрирован (всего: %d)", w.ID, count)
}

func (d *Dispatcher) Unregister(slot *WorkerSlot) {
	d.mu.Lock()
	for i, w := range d.workers {
		if w == slot {
			d.workers = append(d.workers[:i], d.workers[i+1:]...)
			break
		}
	}
	remaining := len(d.workers)
	d.mu.Unlock()
	log.Printf("[ДИСП] Воркер #%d отключён (осталось: %d)", slot.ID, remaining)
}

func (d *Dispatcher) readLoop() {
	defer d.wg.Done()

	buf := make([]byte, readBufSize)
	var schedulerScratch [schedulerMaxScanWindow]int32
	for {
		if err := d.ctx.Err(); err != nil {
			return
		}

		n, addr, err := d.localConn.ReadFrom(buf)
		if err != nil {
			if d.ctx.Err() != nil {
				return
			}
			time.Sleep(10 * time.Millisecond)
			continue
		}

		d.clientAddr.Store(&addr)
		atomic.AddInt64(&d.stats.TotalBytesUp, int64(n))

		d.mu.Lock()
		nw := len(d.workers)
		if nw == 0 {
			d.mu.Unlock()
			continue
		}

		startIdx := d.rrIndex % nw
		bestIdx, bestQueued := d.pickLeastQueuedWorker(
			startIdx,
			minInt(nw, schedulerProbeWindow),
			workerSendBuf+1,
			schedulerScratch[:],
		)
		if bestIdx == -1 && nw > schedulerProbeWindow {
			bestIdx, bestQueued = d.pickLeastQueuedWorker(
				(startIdx+schedulerProbeWindow)%nw,
				nw-schedulerProbeWindow,
				bestQueued,
				schedulerScratch[:],
			)
		}
		if bestIdx == -1 {
			d.rrIndex = (startIdx + 1) % nw
			atomic.AddInt64(&d.stats.DroppedPackets, 1)
			d.mu.Unlock()
			continue
		}

		pkt := copyPacket(buf[:n])
		w := d.workers[bestIdx]
		select {
		case w.SendCh <- pkt:
			d.rrIndex = (bestIdx + 1) % nw
			atomic.AddInt64(&d.stats.PacketsUp, 1)
			atomic.AddInt64(&w.QueuedPackets, 1)
			atomic.AddInt64(&w.QueuedBytes, int64(n))
		default:
			d.rrIndex = (bestIdx + 1) % nw
			atomic.AddInt64(&d.stats.DroppedPackets, 1)
			releasePacket(pkt)
		}
		d.mu.Unlock()
	}
}

func (d *Dispatcher) pickLeastQueuedWorker(startIdx, limit, bestQueued int, scratch []int32) (int, int) {
	nw := len(d.workers)
	if nw == 0 || limit <= 0 || len(scratch) == 0 {
		return -1, bestQueued
	}
	if limit > nw {
		limit = nw
	}

	bestIdx := -1
	capacity := cap(d.workers[startIdx%nw].SendCh)
	for offset := 0; offset < limit; {
		batchSize := minInt(limit-offset, len(scratch))
		for i := 0; i < batchSize; i++ {
			idx := (startIdx + offset + i) % nw
			scratch[i] = int32(len(d.workers[idx].SendCh))
		}

		relativeIdx, queued := pickLeastQueuedByQueueLens(scratch[:batchSize], capacity, bestQueued)
		if relativeIdx >= 0 {
			bestIdx = (startIdx + offset + relativeIdx) % nw
			bestQueued = queued
		}
		if queued == 0 {
			break
		}
		offset += batchSize
	}
	return bestIdx, bestQueued
}

func (d *Dispatcher) writeLoop() {
	defer d.wg.Done()

	for {
		select {
		case <-d.ctx.Done():
			return
		case pkt := <-d.ReturnCh:
			addrPtr := d.clientAddr.Load()
			if addrPtr == nil {
				atomic.AddInt64(&d.stats.DroppedPackets, 1)
				releasePacket(pkt)
				continue
			}
			addr := *addrPtr
			if _, err := d.localConn.WriteTo(pkt, addr); err != nil {
				atomic.AddInt64(&d.stats.DroppedPackets, 1)
				releasePacket(pkt)
				if d.ctx.Err() != nil {
					return
				}
				continue
			}
			atomic.AddInt64(&d.stats.TotalBytesDown, int64(len(pkt)))
			atomic.AddInt64(&d.stats.PacketsDown, 1)
			releasePacket(pkt)
		}
	}
}
