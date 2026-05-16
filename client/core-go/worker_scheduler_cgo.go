//go:build cgo

package main

/*
#cgo CFLAGS: -O3
#include "worker_scheduler.h"
*/
import "C"
import "unsafe"

func pickLeastQueuedByQueueLens(queueLens []int32, capacity, bestQueued int) (int, int) {
	if len(queueLens) == 0 || capacity <= 0 {
		return -1, bestQueued
	}

	outQueued := C.int(bestQueued)
	relativeIdx := C.nxiw_pick_least_queued(
		(*C.int32_t)(unsafe.Pointer(&queueLens[0])),
		C.int(len(queueLens)),
		C.int(capacity),
		C.int(bestQueued),
		&outQueued,
	)
	if relativeIdx < 0 {
		return -1, int(outQueued)
	}
	return int(relativeIdx), int(outQueued)
}
