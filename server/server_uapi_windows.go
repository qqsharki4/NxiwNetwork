//go:build windows

package main

import (
	"fmt"
	"net"
)

func listenUAPI(ifaceName string) (net.Listener, error) {
	return nil, fmt.Errorf("uapi listener is not supported on windows for %s", ifaceName)
}
