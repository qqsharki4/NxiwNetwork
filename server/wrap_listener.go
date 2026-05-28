package main

import (
	"crypto/cipher"
	"errors"
	"fmt"
	"log"
	"net"
	"sync/atomic"
	"time"

	dtlsnet "github.com/pion/dtls/v3/pkg/net"
	pionudp "github.com/pion/transport/v4/udp"
)

func listenWrapped(addr *net.UDPAddr, keys *wrapKeyStore) (dtlsnet.PacketListener, error) {
	if keys == nil || keys.Count() == 0 {
		return nil, errors.New("wrap: no active keys")
	}
	inner, err := pionudp.Listen("udp", addr)
	if err != nil {
		return nil, fmt.Errorf("wrap: udp listen: %w", err)
	}
	return &wrapPacketListener{
		inner: dtlsnet.PacketListenerFromListener(inner),
		keys:  keys,
	}, nil
}

type wrapPacketListener struct {
	inner dtlsnet.PacketListener
	keys  *wrapKeyStore
}

func (l *wrapPacketListener) Accept() (net.PacketConn, net.Addr, error) {
	pc, addr, err := l.inner.Accept()
	if err != nil {
		return pc, addr, err
	}
	return &wrapPacketConn{inner: pc, keys: l.keys}, addr, nil
}

func (l *wrapPacketListener) Close() error   { return l.inner.Close() }
func (l *wrapPacketListener) Addr() net.Addr { return l.inner.Addr() }

type wrapPacketConn struct {
	inner     net.PacketConn
	keys      *wrapKeyStore
	key       []byte
	mode      int32
	authLog   int32
	obfsCfg   *ObfsConfig
	obfsWrite *ObfsState
	obfsAEAD  cipher.AEAD
	readBuf   []byte
	wrapBuf   []byte
}

const (
	wrapModeUnknown int32 = iota
	wrapModeRaw
	wrapModeObfs
)

func (c *wrapPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	readLen := len(p) + 80
	if cap(c.readBuf) < readLen {
		c.readBuf = make([]byte, readLen)
	}
	buf := c.readBuf[:readLen]
	n, addr, err := c.inner.ReadFrom(buf)
	if err != nil {
		return 0, addr, err
	}
	raw := buf[:n]

	switch atomic.LoadInt32(&c.mode) {
	case wrapModeRaw:
		return c.readRaw(p, raw, addr)
	case wrapModeObfs:
		m, unwrapErr := obfsUnwrapPacket(c.obfsAEAD, raw, p)
		if unwrapErr != nil {
			return 0, addr, fmt.Errorf("obfs unwrap: %w", unwrapErr)
		}
		return m, addr, nil
	default:
	}

	if !obfsIsRTPPacket(raw) {
		atomic.StoreInt32(&c.mode, wrapModeRaw)
		return c.readRaw(p, raw, addr)
	}

	if c.keys == nil || c.keys.Count() == 0 {
		atomic.StoreInt32(&c.mode, wrapModeRaw)
		return c.readRaw(p, raw, addr)
	}

	if atomic.LoadInt32(&c.mode) == wrapModeUnknown {
		key, m, unwrapErr := c.keys.Unwrap(raw, p)
		if unwrapErr != nil {
			if atomic.CompareAndSwapInt32(&c.authLog, 0, 1) {
				log.Printf("[WRAP] Отказ: RTP AEAD auth failed from %s (keys=%d)", addr.String(), c.keys.Count())
			}
			return 0, addr, unwrapErr
		}
		c.key = key
		aead, err := newObfsAEAD(key)
		if err != nil {
			return 0, addr, err
		}
		c.obfsAEAD = aead
		c.obfsCfg = NewObfsConfig()
		c.obfsWrite = NewObfsState()
		atomic.StoreInt32(&c.mode, wrapModeObfs)
		if atomic.CompareAndSwapInt32(&c.authLog, 0, 1) {
			log.Printf("[WRAP] OK: ключ выбран для %s (keys=%d)", addr.String(), c.keys.Count())
		}
		return m, addr, nil
	}

	return c.readRaw(p, raw, addr)
}

func (c *wrapPacketConn) readRaw(p, raw []byte, addr net.Addr) (int, net.Addr, error) {
	if len(raw) > len(p) {
		return 0, addr, errors.New("raw packet larger than destination buffer")
	}
	copy(p, raw)
	return len(raw), addr, nil
}

func (c *wrapPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if atomic.LoadInt32(&c.mode) == wrapModeRaw {
		return c.inner.WriteTo(p, addr)
	}
	if atomic.LoadInt32(&c.mode) != wrapModeObfs || len(c.key) != wrapKeyLen {
		return c.inner.WriteTo(p, addr)
	}
	if c.obfsCfg == nil || c.obfsWrite == nil {
		c.obfsCfg = NewObfsConfig()
		c.obfsWrite = NewObfsState()
	}
	need := obfsMaxWireLen(len(p), c.obfsCfg)
	if cap(c.wrapBuf) < need {
		c.wrapBuf = make([]byte, need)
	}
	wrapped, wrapErr := obfsWrapPacketTo(c.obfsAEAD, p, c.obfsCfg, c.obfsWrite, c.wrapBuf[:need])
	if wrapErr != nil {
		return 0, fmt.Errorf("obfs wrap: %w", wrapErr)
	}
	if _, err := c.inner.WriteTo(wrapped, addr); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *wrapPacketConn) Close() error                       { return c.inner.Close() }
func (c *wrapPacketConn) LocalAddr() net.Addr                { return c.inner.LocalAddr() }
func (c *wrapPacketConn) SetDeadline(t time.Time) error      { return c.inner.SetDeadline(t) }
func (c *wrapPacketConn) SetReadDeadline(t time.Time) error  { return c.inner.SetReadDeadline(t) }
func (c *wrapPacketConn) SetWriteDeadline(t time.Time) error { return c.inner.SetWriteDeadline(t) }
