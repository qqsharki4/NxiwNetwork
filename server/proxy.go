package main

import (
	"context"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/dtls/v3"
	"golang.zx2c4.com/wireguard/device"
)

var bufPool = sync.Pool{
	New: func() interface{} {
		b := make([]byte, 1600)
		return &b
	},
}

func getBuf() *[]byte  { return bufPool.Get().(*[]byte) }
func putBuf(b *[]byte) { bufPool.Put(b) }

func isNetTimeout(err error) bool {
	ne, ok := err.(net.Error)
	return ok && ne.Timeout()
}

func handleConn(ctx context.Context, clientConn net.Conn, wgEndpoint string, wgDev *device.Device, keys *wgKeys) {
	atomic.AddInt64(&totalConns, 1)

	var connDeviceID string
	var connPassword string
	var connIsMainPass bool

	dtlsConn, ok := clientConn.(*dtls.Conn)
	if !ok {
		return
	}

	hctx, hcancel := context.WithTimeout(ctx, 30*time.Second)
	if err := dtlsConn.HandshakeContext(hctx); err != nil {
		hcancel()
		return
	}
	hcancel()

	atomic.AddInt32(&activeConns, 1)
	defer atomic.AddInt32(&activeConns, -1)

	buf := make([]byte, 1600)
	clientConn.SetReadDeadline(time.Now().Add(30 * time.Second))
	n, err := clientConn.Read(buf)
	if err != nil {
		return
	}
	clientConn.SetReadDeadline(time.Time{})

	firstPacket := buf[:n]
	firstStr := string(firstPacket)

	if _, ok := parseProtocolHello(firstPacket); ok {
		clientConn.Write(buildProtocolHelloOK())
		clientConn.SetReadDeadline(time.Now().Add(30 * time.Second))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
		firstStr = string(firstPacket)
	}

	if configReq, ok := parseConfigRequest(firstPacket); ok {
		result := handleConfigRequest(configReq, wgDev, keys)
		if len(result.Response) > 0 {
			if _, err := clientConn.Write(result.Response); err != nil {
				return
			}
		}
		if !result.Continue {
			return
		}
		connDeviceID = result.DeviceID
		connPassword = result.Password
		connIsMainPass = result.IsMainPass

		clientConn.SetReadDeadline(time.Now().Add(5 * time.Minute))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
		firstStr = string(firstPacket)
	}

	if firstStr == "READY" {
		clientConn.Write([]byte("READY_OK"))
		clientConn.SetReadDeadline(time.Now().Add(10 * time.Minute))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
	}

	// WG прокси
	wgConn, err := net.Dial("udp", wgEndpoint)
	if err != nil {
		return
	}
	defer wgConn.Close()

	if uc, ok := wgConn.(*net.UDPConn); ok {
		uc.SetReadBuffer(2 * 1024 * 1024)
		uc.SetWriteBuffer(2 * 1024 * 1024)
	}

	if _, err := wgConn.Write(firstPacket); err != nil {
		if ctx.Err() == nil {
			log.Printf("[ПРОКСИ] Ошибка первичной записи client->wg: %v", err)
		}
		return
	}
	atomic.AddInt64(&totalBytesFromClient, int64(len(firstPacket)))

	// Трекинг онлайн-статуса
	if connDeviceID != "" {
		activeDevicesMu.Lock()
		activeDevices[connDeviceID]++
		activeDevicesMu.Unlock()
		defer func() {
			activeDevicesMu.Lock()
			activeDevices[connDeviceID]--
			if activeDevices[connDeviceID] <= 0 {
				delete(activeDevices, connDeviceID)
			}
			activeDevicesMu.Unlock()
		}()
	}

	pctx, pcancel := context.WithCancel(ctx)
	defer pcancel()

	context.AfterFunc(pctx, func() {
		clientConn.SetDeadline(time.Now())
		wgConn.SetDeadline(time.Now())
	})

	var proxyWg sync.WaitGroup
	proxyWg.Add(3)

	// Keepalive для спасения от Doze (Push ping)
	go func() {
		defer proxyWg.Done()
		defer pcancel()
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-pctx.Done():
				return
			case <-ticker.C:
				if _, err := clientConn.Write([]byte("WAKEUP")); err != nil {
					if pctx.Err() == nil {
						log.Printf("[ПРОКСИ] Ошибка keepalive write: %v", err)
					}
					return
				}
			}
		}
	}()

	// Клиент → WG
	go func() {
		defer proxyWg.Done()
		defer pcancel()
		b := getBuf()
		defer putBuf(b)
		for {
			clientConn.SetReadDeadline(time.Now().Add(90 * time.Second))
			nn, err := clientConn.Read(*b)
			if err != nil {
				if pctx.Err() == nil && !isNetTimeout(err) {
					log.Printf("[ПРОКСИ] Ошибка чтения client->wg: %v", err)
				}
				return
			}
			if nn == 6 && string((*b)[:6]) == "WAKEUP" {
				continue
			}
			atomic.AddInt64(&totalBytesFromClient, int64(nn))
			// Per-password upload tracking
			if connIsMainPass {
				atomic.AddInt64(&mainPassUp, int64(nn))
			} else if connPassword != "" {
				dbMutex.Lock()
				if e, ok := db.Passwords[connPassword]; ok {
					e.UpBytes += int64(nn)
				}
				dbMutex.Unlock()
			}
			if _, err := wgConn.Write((*b)[:nn]); err != nil {
				if pctx.Err() == nil {
					log.Printf("[ПРОКСИ] Ошибка записи client->wg: %v", err)
				}
				return
			}
		}
	}()

	// WG → Клиент
	go func() {
		defer proxyWg.Done()
		defer pcancel()
		b := getBuf()
		defer putBuf(b)
		for {
			wgConn.SetReadDeadline(time.Now().Add(90 * time.Second))
			nn, err := wgConn.Read(*b)
			if isNetTimeout(err) {
				if pctx.Err() != nil {
					return
				}
				continue
			}
			if err != nil {
				if pctx.Err() == nil {
					log.Printf("[ПРОКСИ] Ошибка чтения wg->client: %v", err)
				}
				return
			}
			atomic.AddInt64(&totalBytesToClient, int64(nn))
			// Per-password download tracking
			if connIsMainPass {
				atomic.AddInt64(&mainPassDown, int64(nn))
			} else if connPassword != "" {
				dbMutex.Lock()
				if e, ok := db.Passwords[connPassword]; ok {
					e.DownBytes += int64(nn)
				}
				dbMutex.Unlock()
			}
			if _, err := clientConn.Write((*b)[:nn]); err != nil {
				if pctx.Err() == nil {
					log.Printf("[ПРОКСИ] Ошибка записи wg->client: %v", err)
				}
				return
			}
		}
	}()

	proxyWg.Wait()
}
