#ifndef NXIW_RS_BACKEND_H
#define NXIW_RS_BACKEND_H

#include <stddef.h>
#include <stdint.h>

uint32_t nxiw_backend_contract_version(void);
uint32_t nxiw_fast_checksum(const uint8_t *data, size_t len);

#endif
