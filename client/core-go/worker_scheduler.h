#ifndef NXIW_NATIVE_SCHED_H
#define NXIW_NATIVE_SCHED_H

#include <stdint.h>

int nxiw_pick_least_queued(
    const int32_t *queued,
    int count,
    int capacity,
    int best_queued,
    int *out_queued
);

#endif
