package main

import (
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
)

const (
	wgIfaceName    = "wg0"
	wgServerAddr   = "10.66.66.1"
	wgClientAddr   = "10.66.66.2"
	wgClientCIDR   = wgClientAddr + "/32"
	wgServerCIDR   = wgServerAddr + "/24"
	internalWGPort = 56001
	dns            = "1.1.1.1"
	wgMTU          = 1280
	keepalive      = 25
)

func main() {
	listen := flag.String("listen", "0.0.0.0:56000", "DTLS адрес")
	wgPort := flag.Int("wg-port", internalWGPort, "WireGuard UDP порт")
	configDir := flag.String("config-dir", "/etc/wireguard", "директория конфигурации")
	mainPass := flag.String("password", "", "пароль владельца")
	adminID := flag.String("admin", "", "Telegram Admin ID")
	botToken := flag.String("bot-token", "", "Telegram Bot Token")
	policyDefaultDNS := flag.String("policy-default-dns", defaultPolicyDNS, "DNS по умолчанию для клиентского WireGuard-конфига")
	policyDefaultMTU := flag.Int("policy-default-mtu", defaultPolicyMTU, "MTU по умолчанию для клиентского WireGuard-конфига")
	policyAllowCustomDNS := flag.Bool("policy-allow-custom-dns", true, "разрешить клиенту передавать custom DNS")
	policyAllowCustomMTU := flag.Bool("policy-allow-custom-mtu", true, "разрешить клиенту передавать custom MTU")
	policyMaxWorkers := flag.Int("policy-max-workers", defaultProtocolMaxWorkers, "максимум воркеров, который нода сообщает клиенту")
	policyMinMTU := flag.Int("policy-mtu-min", defaultProtocolMinMTU, "минимальный custom MTU")
	policyMaxMTU := flag.Int("policy-mtu-max", defaultProtocolMaxMTU, "максимальный custom MTU")
	flag.Parse()
	configureNodePolicy(
		*policyDefaultDNS,
		*policyDefaultMTU,
		*policyAllowCustomDNS,
		*policyAllowCustomMTU,
		*policyMaxWorkers,
		*policyMinMTU,
		*policyMaxMTU,
	)

	_ = wgPort // WG порт задаётся через internalWGPort (56001)

	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
	log.Println("══════════════════════════════════════════")
	log.Println("   NxiwNetwork Server v2 (Multi-User)")
	log.Println("══════════════════════════════════════════")
	log.Printf("[POLICY] DNS=%s MTU=%d range=%d-%d custom_dns=%t custom_mtu=%t max_workers=%d",
		nodePolicy.DefaultDNS,
		nodePolicy.DefaultMTU,
		nodePolicy.MinMTU,
		nodePolicy.MaxMTU,
		nodePolicy.AllowCustomDNS,
		nodePolicy.AllowCustomMTU,
		nodePolicy.MaxWorkers,
	)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-sig
		cancel()
		time.Sleep(2 * time.Second)
		os.Exit(0)
	}()

	initDB(*configDir, *mainPass, *adminID, *botToken)
	defer closeDB()

	keys, err := loadOrGenerateKeys(*configDir)
	if err != nil {
		log.Fatalf("[WG] Ключи: %v", err)
	}

	enableBBR()

	wgDev, err := startUserspaceWG(keys)
	if err != nil {
		log.Fatalf("[WG] Запуск: %v", err)
	}
	defer func() {
		wgDev.Close()
		runCmdSilent("ip", "link", "del", wgIfaceName)
	}()

	go statsLoop(ctx, *configDir)
	go botLoop(*botToken, *adminID, wgDev)

	addr, _ := net.ResolveUDPAddr("udp", *listen)
	cert, _ := selfsign.GenerateSelfSigned()
	dtlsCfg := &dtls.Config{
		Certificates:          []tls.Certificate{cert},
		ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
		CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
		ConnectionIDGenerator: dtls.RandomCIDGenerator(8),
	}

	listener, err := dtls.Listen("udp", addr, dtlsCfg)
	if err != nil {
		log.Fatalf("[DTLS] %v", err)
	}
	context.AfterFunc(ctx, func() { listener.Close() })

	wgEndpoint := fmt.Sprintf("127.0.0.1:%d", internalWGPort)

	log.Printf("   DTLS: %s | WG: %s | NAT: %s", *listen, wgEndpoint, natType)
	log.Println("[SERVER] Готов")

	var wg sync.WaitGroup
	for {
		dtlsConn, err := listener.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				wg.Wait()
				return
			default:
			}
			continue
		}
		wg.Add(1)
		go func(c net.Conn) {
			defer wg.Done()
			defer c.Close()
			handleConn(ctx, c, wgEndpoint, wgDev, keys)
		}(dtlsConn)
	}
}
