//go:build networkscan_deps

package main

import (
	_ "github.com/containers/podman/v5/pkg/bindings"
	_ "github.com/moby/moby/client"
	_ "github.com/vishvananda/netlink"
	_ "go4.org/netipx"
	_ "k8s.io/client-go/kubernetes"
	_ "libvirt.org/go/libvirt"
)
