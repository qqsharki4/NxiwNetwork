//go:build !cgo

package main

func pickLeastQueuedByQueueLens(queueLens []int32, capacity, bestQueued int) (int, int) {
	bestIdx := -1
	for i, queued := range queueLens {
		value := int(queued)
		if value < 0 || value >= capacity {
			continue
		}
		if value < bestQueued {
			bestIdx = i
			bestQueued = value
			if value == 0 {
				break
			}
		}
	}
	return bestIdx, bestQueued
}
