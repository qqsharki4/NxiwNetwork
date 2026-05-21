package main

import (
	"log"
	"os"
	"os/exec"
	"strings"
)

func enableBBR() {
	log.Println("[SYS] Оптимизация TCP...")
	out, _ := runCmd("bash", "-c", "sysctl net.ipv4.tcp_congestion_control")
	if strings.Contains(out, "bbr") {
		log.Println("[SYS] BBR уже активен ✓")
		return
	}
	cmds := [][]string{
		{"sysctl", "-w", "net.core.default_qdisc=fq"},
		{"sysctl", "-w", "net.ipv4.tcp_congestion_control=bbr"},
		{"sysctl", "-w", "net.core.rmem_max=25165824"},
		{"sysctl", "-w", "net.core.wmem_max=25165824"},
		{"sysctl", "-w", "net.ipv4.tcp_rmem=4096 87380 25165824"},
		{"sysctl", "-w", "net.ipv4.tcp_wmem=4096 65536 25165824"},
	}
	for _, cmd := range cmds {
		runCmd(cmd[0], cmd[1:]...)
	}
	log.Println("[SYS] BBR включен ✓")
}

func runCmd(name string, args ...string) (string, error) {
	out, err := exec.Command(name, args...).CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

func runCmdSilent(name string, args ...string) string {
	out, _ := exec.Command(name, args...).CombinedOutput()
	return strings.TrimSpace(string(out))
}

func getDefaultInterface() string {
	out := runCmdSilent("bash", "-c", "ip route show default | awk '/default/ {print $5}' | head -1")
	if out != "" {
		return strings.TrimSpace(out)
	}
	out = runCmdSilent("bash", "-c", "ip -o link show | awk -F': ' '{print $2}' | grep -v -E 'lo|wg|tun' | head -1")
	if out != "" {
		return strings.TrimSpace(out)
	}
	return "eth0"
}

func setupFullConeNAT(wgIface string) error {
	log.Println("[NAT] ══════════════════════════════════════")

	os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1"), 0644)
	os.WriteFile("/proc/sys/net/ipv6/conf/"+wgIface+"/disable_ipv6", []byte("1"), 0644)
	os.WriteFile("/proc/sys/net/ipv6/conf/all/disable_ipv6", []byte("1"), 0644)

	extIface := getDefaultInterface()
	log.Printf("[NAT] Внешний: %s", extIface)

	// Очистка старых MASQUERADE правил
	for i := 0; i < 5; i++ {
		exec.Command("iptables", "-t", "nat", "-D", "POSTROUTING", "-s", wgServerCIDR, "-o", extIface, "-j", "MASQUERADE").Run()
	}

	// Установка MASQUERADE (основной NAT)
	exec.Command("iptables", "-t", "nat", "-I", "POSTROUTING", "1", "-s", wgServerCIDR, "-o", extIface, "-j", "MASQUERADE").Run()
	natType = "MASQUERADE ✅"

	setupForwardRules(wgIface)
	log.Printf("[NAT] Режим: %s", natType)
	log.Println("[NAT] ══════════════════════════════════════")
	return nil
}

func setupForwardRules(wgIface string) {
	for i := 0; i < 5; i++ {
		exec.Command("iptables", "-D", "FORWARD", "-i", wgIface, "-j", "ACCEPT").Run()
		exec.Command("iptables", "-D", "FORWARD", "-o", wgIface, "-j", "ACCEPT").Run()
	}
	exec.Command("iptables", "-A", "FORWARD", "-i", wgIface, "-j", "ACCEPT").Run()
	exec.Command("iptables", "-A", "FORWARD", "-o", wgIface, "-j", "ACCEPT").Run()
	exec.Command("iptables", "-A", "FORWARD", "-m", "state", "--state", "ESTABLISHED,RELATED", "-j", "ACCEPT").Run()
}
