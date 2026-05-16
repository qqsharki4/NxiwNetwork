package main

import "sync"

const packetPoolBufSize = 2048

var packetPool = sync.Pool{
	New: func() any {
		return make([]byte, packetPoolBufSize)
	},
}

func copyPacket(src []byte) []byte {
	if len(src) == 0 {
		return nil
	}

	dst := packetPool.Get().([]byte)
	if cap(dst) < len(src) {
		if cap(dst) == packetPoolBufSize {
			packetPool.Put(dst[:packetPoolBufSize])
		}
		dst = make([]byte, len(src))
	}
	dst = dst[:len(src)]
	copy(dst, src)
	return dst
}

func releasePacket(pkt []byte) {
	if cap(pkt) != packetPoolBufSize {
		return
	}
	packetPool.Put(pkt[:packetPoolBufSize])
}

func drainPacketChannel(ch <-chan []byte) {
	for {
		select {
		case pkt := <-ch:
			releasePacket(pkt)
		default:
			return
		}
	}
}
