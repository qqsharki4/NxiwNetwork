#include "backend.h"

uint32_t nxiw_backend_contract_version(void) {
    return 1;
}

uint32_t nxiw_fast_checksum(const uint8_t *data, size_t len) {
    uint32_t hash = 2166136261u;
    if (data == 0) {
        return hash;
    }

    for (size_t i = 0; i < len; i++) {
        hash ^= data[i];
        hash *= 16777619u;
    }
    return hash;
}
