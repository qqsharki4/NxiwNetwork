//go:build !windows

package main

import (
	"net"

	"golang.zx2c4.com/wireguard/ipc"
)

func listenUAPI(ifaceName string) (net.Listener, error) {
	file, err := ipc.UAPIOpen(ifaceName)
	if err != nil {
		return nil, err
	}
	listener, err := ipc.UAPIListen(ifaceName, file)
	_ = file.Close()
	return listener, err
}
