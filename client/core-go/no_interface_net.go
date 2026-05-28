package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/pion/transport/v4"
)

type noInterfaceNet struct{}

var _ transport.Net = noInterfaceNet{}

func (noInterfaceNet) ListenPacket(network string, address string) (net.PacketConn, error) {
	return net.ListenPacket(network, address)
}

func (noInterfaceNet) ListenUDP(network string, locAddr *net.UDPAddr) (transport.UDPConn, error) {
	return net.ListenUDP(network, locAddr)
}

func (noInterfaceNet) ListenTCP(network string, laddr *net.TCPAddr) (transport.TCPListener, error) {
	l, err := net.ListenTCP(network, laddr)
	if err != nil {
		return nil, err
	}
	return tcpListener{l}, nil
}

func (noInterfaceNet) Dial(network, address string) (net.Conn, error) {
	return net.Dial(network, address)
}

func (noInterfaceNet) DialUDP(network string, laddr, raddr *net.UDPAddr) (transport.UDPConn, error) {
	return net.DialUDP(network, laddr, raddr)
}

func (noInterfaceNet) DialTCP(network string, laddr, raddr *net.TCPAddr) (transport.TCPConn, error) {
	return net.DialTCP(network, laddr, raddr)
}

func (noInterfaceNet) ResolveIPAddr(network, address string) (*net.IPAddr, error) {
	return net.ResolveIPAddr(network, address)
}

func (noInterfaceNet) ResolveUDPAddr(network, address string) (*net.UDPAddr, error) {
	return net.ResolveUDPAddr(network, address)
}

func (noInterfaceNet) ResolveTCPAddr(network, address string) (*net.TCPAddr, error) {
	return net.ResolveTCPAddr(network, address)
}

func (noInterfaceNet) Interfaces() ([]*transport.Interface, error) {
	return nil, nil
}

func (noInterfaceNet) InterfaceByIndex(index int) (*transport.Interface, error) {
	return nil, fmt.Errorf("%w: index=%d", transport.ErrInterfaceNotFound, index)
}

func (noInterfaceNet) InterfaceByName(name string) (*transport.Interface, error) {
	return nil, fmt.Errorf("%w: %s", transport.ErrInterfaceNotFound, name)
}

func (noInterfaceNet) CreateDialer(d *net.Dialer) transport.Dialer {
	return netDialer{d}
}

func (noInterfaceNet) CreateListenConfig(lc *net.ListenConfig) transport.ListenConfig {
	return netListenConfig{lc}
}

type tcpListener struct {
	*net.TCPListener
}

func (l tcpListener) AcceptTCP() (transport.TCPConn, error) {
	return l.TCPListener.AcceptTCP()
}

type netDialer struct {
	*net.Dialer
}

func (d netDialer) Dial(network, address string) (net.Conn, error) {
	return d.Dialer.Dial(network, address)
}

type netListenConfig struct {
	*net.ListenConfig
}

func (lc netListenConfig) Listen(ctx context.Context, network, address string) (net.Listener, error) {
	return lc.ListenConfig.Listen(ctx, network, address)
}

func (lc netListenConfig) ListenPacket(ctx context.Context, network, address string) (net.PacketConn, error) {
	return lc.ListenConfig.ListenPacket(ctx, network, address)
}

var _ transport.TCPConn = (*net.TCPConn)(nil)
var _ io.ReaderFrom = (*net.TCPConn)(nil)
var _ interface {
	SetDeadline(time.Time) error
} = (*net.TCPConn)(nil)
