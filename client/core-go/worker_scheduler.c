//go:build cgo

#include "worker_scheduler.h"

int nxiw_pick_least_queued(
    const int32_t *queued,
    int count,
    int capacity,
    int best_queued,
    int *out_queued
) {
    int best_index = -1;
    int best = best_queued;

    if (queued == 0 || count <= 0 || capacity <= 0) {
        if (out_queued != 0) {
            *out_queued = best;
        }
        return -1;
    }

    for (int i = 0; i < count; i++) {
        int value = queued[i];
        if (value < 0 || value >= capacity) {
            continue;
        }
        if (value < best) {
            best_index = i;
            best = value;
            if (value == 0) {
                break;
            }
        }
    }

    if (out_queued != 0) {
        *out_queued = best;
    }
    return best_index;
}
