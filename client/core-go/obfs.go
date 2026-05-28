package main

import (
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"
)

type ObfsConfig struct {
	SSRC        uint32
	PayloadType uint8
	PaddingMax  int
}

func NewObfsConfig() *ObfsConfig {
	var buf [4]byte
	_, _ = rand.Read(buf[:])
	return &ObfsConfig{
		SSRC:        binary.BigEndian.Uint32(buf[:]),
		PayloadType: 111,
		PaddingMax:  24,
	}
}

type ObfsState struct {
	mu  sync.Mutex
	seq uint16
	ts  uint32
	rng uint64
}

func NewObfsState() *ObfsState {
	var buf [14]byte
	_, _ = rand.Read(buf[:])
	rng := binary.BigEndian.Uint64(buf[6:14])
	if rng == 0 {
		rng = 0x9e3779b97f4a7c15
	}
	return &ObfsState{
		seq: binary.BigEndian.Uint16(buf[0:2]),
		ts:  binary.BigEndian.Uint32(buf[2:6]),
		rng: rng,
	}
}

func newObfsAEAD(key []byte) (cipher.AEAD, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, fmt.Errorf("obfs: cipher init: %w", err)
	}
	return aead, nil
}

func obfsBuildNonce(dst *[12]byte, ssrc uint32, seq uint16, ts uint32) []byte {
	*dst = [12]byte{}
	binary.BigEndian.PutUint32(dst[0:4], ssrc)
	binary.BigEndian.PutUint16(dst[4:6], seq)
	binary.BigEndian.PutUint32(dst[8:12], ts)
	return dst[:]
}

func obfsMaxWireLen(payloadLen int, cfg *ObfsConfig) int {
	paddingMax := 1
	if cfg != nil && cfg.PaddingMax > paddingMax {
		paddingMax = cfg.PaddingMax
	}
	return 12 + payloadLen + chacha20poly1305.Overhead + paddingMax
}

func obfsNextRand(seed uint64) uint64 {
	seed ^= seed << 7
	seed ^= seed >> 9
	seed ^= seed << 8
	if seed == 0 {
		return 0x9e3779b97f4a7c15
	}
	return seed
}

func obfsFillPadding(dst []byte, seed uint64) {
	for i := range dst {
		seed = obfsNextRand(seed)
		dst[i] = byte(seed)
	}
}

func obfsWrapPacketTo(aead cipher.AEAD, payload []byte, cfg *ObfsConfig, state *ObfsState, out []byte) ([]byte, error) {
	if aead == nil {
		return nil, errors.New("obfs: nil cipher")
	}
	if len(payload) == 0 {
		return nil, errors.New("obfs: empty payload")
	}
	if cfg == nil || state == nil {
		return nil, errors.New("obfs: nil config/state")
	}

	state.mu.Lock()
	seq := state.seq
	ts := state.ts
	state.seq++
	state.ts += 960
	padRand := 0
	padSeed := state.rng
	if cfg.PaddingMax > 0 {
		state.rng = obfsNextRand(state.rng)
		padSeed = state.rng
		padRand = int(byte(padSeed)) % cfg.PaddingMax
	}
	state.mu.Unlock()

	var nonce [12]byte
	nonceBytes := obfsBuildNonce(&nonce, cfg.SSRC, seq, ts)
	padTotal := padRand + 1
	outLen := 12 + len(payload) + chacha20poly1305.Overhead + padTotal
	if len(out) < outLen {
		return nil, fmt.Errorf("obfs: output buffer too small (%d < %d)", len(out), outLen)
	}
	out = out[:outLen]

	out[0] = 0x80 | 0x20
	out[1] = cfg.PayloadType & 0x7F
	binary.BigEndian.PutUint16(out[2:4], seq)
	binary.BigEndian.PutUint32(out[4:8], ts)
	binary.BigEndian.PutUint32(out[8:12], cfg.SSRC)

	sealed := aead.Seal(out[12:12], nonceBytes, payload, out[:12])
	padStart := 12 + len(sealed)
	if padRand > 0 {
		obfsFillPadding(out[padStart:padStart+padRand], padSeed)
	}
	out[outLen-1] = byte(padTotal)
	return out, nil
}

func obfsUnwrapPacket(aead cipher.AEAD, wire, dst []byte) (int, error) {
	if aead == nil {
		return 0, errors.New("obfs: nil cipher")
	}
	if len(wire) < 13 {
		return 0, errors.New("obfs: packet too short")
	}
	if (wire[0] >> 6) != 2 {
		return 0, errors.New("obfs: not RTP v2")
	}

	seq := binary.BigEndian.Uint16(wire[2:4])
	ts := binary.BigEndian.Uint32(wire[4:8])
	ssrc := binary.BigEndian.Uint32(wire[8:12])

	payloadEnd := len(wire)
	if wire[0]&0x20 != 0 {
		padLen := int(wire[len(wire)-1])
		if padLen == 0 || padLen > payloadEnd-12 {
			return 0, fmt.Errorf("obfs: invalid padding length %d", padLen)
		}
		payloadEnd -= padLen
	}

	ciphertextLen := payloadEnd - 12
	if ciphertextLen <= chacha20poly1305.Overhead {
		return 0, errors.New("obfs: no payload")
	}
	if ciphertextLen-chacha20poly1305.Overhead > len(dst) {
		return 0, errors.New("obfs: dst buffer too small")
	}

	var nonce [12]byte
	nonceBytes := obfsBuildNonce(&nonce, ssrc, seq, ts)
	plain, err := aead.Open(dst[:0], nonceBytes, wire[12:payloadEnd], wire[:12])
	if err != nil {
		return 0, fmt.Errorf("obfs: auth: %w", err)
	}
	return len(plain), nil
}

func obfsIsRTPPacket(wire []byte) bool {
	if len(wire) < 13 {
		return false
	}
	if (wire[0] >> 6) != 2 {
		return false
	}
	return wire[1]&0x7F == 111
}
