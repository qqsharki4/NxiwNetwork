package main

import (
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"sync/atomic"
	"time"
)

type configResponse struct {
	Type         string         `json:"type"`
	Protocol     int            `json:"protocol"`
	Config       string         `json:"config"`
	Error        string         `json:"error"`
	Reason       string         `json:"reason"`
	Capabilities []string       `json:"capabilities"`
	Policy       protocolPolicy `json:"policy"`
}

type protocolHelloOK struct {
	Type         string         `json:"type"`
	Protocol     int            `json:"protocol"`
	Server       string         `json:"server"`
	Capabilities []string       `json:"capabilities"`
	Policy       protocolPolicy `json:"policy"`
}

type protocolPolicy struct {
	MaxWorkers       int      `json:"max_workers"`
	CustomDNSAllowed bool     `json:"custom_dns_allowed"`
	Transports       []string `json:"transports"`
}

var protocolV2Disabled atomic.Bool

// RequestConfig запрашивает WireGuard конфиг через DTLS-соединение.
func RequestConfig(conn net.Conn, localPort, deviceID, password, dnsOverride string) (string, error) {
	if !protocolV2Disabled.Load() {
		config, err := requestConfigV2(conn, localPort, deviceID, password, dnsOverride)
		if err == nil {
			return config, nil
		}
		if strings.Contains(err.Error(), "RETRY_LEGACY") {
			return "", err
		}
		return "", err
	}
	return requestConfigLegacy(conn, localPort, deviceID, password, dnsOverride)
}

func requestConfigV2(conn net.Conn, localPort, deviceID, password, dnsOverride string) (string, error) {
	hello := `{"type":"hello","protocol_min":1,"protocol_max":2,"capabilities":["custom_dns","wireguard_config"]}`
	if _, err := conn.Write([]byte(hello)); err != nil {
		protocolV2Disabled.Store(true)
		return "", fmt.Errorf("RETRY_LEGACY: отправка hello: %w", err)
	}

	b := make([]byte, 4096)
	if err := conn.SetReadDeadline(time.Now().Add(3 * time.Second)); err != nil {
		protocolV2Disabled.Store(true)
		return "", fmt.Errorf("RETRY_LEGACY: установка hello дедлайна: %w", err)
	}
	n, err := conn.Read(b)
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil {
		protocolV2Disabled.Store(true)
		return "", fmt.Errorf("RETRY_LEGACY: hello timeout: %w", err)
	}

	var helloOK protocolHelloOK
	if err := json.Unmarshal(b[:n], &helloOK); err != nil || helloOK.Type != "hello_ok" || helloOK.Protocol < 2 {
		protocolV2Disabled.Store(true)
		return "", fmt.Errorf("RETRY_LEGACY: hello unsupported")
	}

	payload := buildJSONConfigRequestPayload(localPort, deviceID, password, dnsOverride)
	if _, err := conn.Write([]byte(payload)); err != nil {
		return "", fmt.Errorf("отправка JSON get_config: %w", err)
	}

	if err := conn.SetReadDeadline(time.Now().Add(15 * time.Second)); err != nil {
		return "", fmt.Errorf("установка дедлайна: %w", err)
	}
	n, err = conn.Read(b)
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil {
		return "", fmt.Errorf("чтение JSON ответа конфига: %w", err)
	}

	resp := string(b[:n])
	config, err := parseConfigResponse(resp)
	if err == nil {
		fmt.Printf("[PROTO] %s\n", describeConfigProtocol("json", resp, config, dnsOverride))
	}
	return config, err
}

func requestConfigLegacy(conn net.Conn, localPort, deviceID, password, dnsOverride string) (string, error) {
	payload, requestMode := buildLegacyConfigRequestPayload(localPort, deviceID, password, dnsOverride)
	if _, err := conn.Write([]byte(payload)); err != nil {
		return "", fmt.Errorf("отправка GETCONF: %w", err)
	}

	b := make([]byte, 4096)
	if err := conn.SetReadDeadline(time.Now().Add(15 * time.Second)); err != nil {
		return "", fmt.Errorf("установка дедлайна: %w", err)
	}
	n, err := conn.Read(b)
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil {
		return "", fmt.Errorf("чтение ответа конфига: %w", err)
	}

	resp := string(b[:n])
	config, err := parseConfigResponse(resp)
	if err == nil {
		fmt.Printf("[PROTO] %s\n", describeConfigProtocol(requestMode, resp, config, dnsOverride))
	}
	return config, err
}

func buildLegacyConfigRequestPayload(localPort, deviceID, password, dnsOverride string) (string, string) {
	fields := []string{localPort, deviceID, password}
	dnsOverride = strings.TrimSpace(dnsOverride)
	if dnsOverride != "" {
		fields = append(fields, dnsOverride, "proto=2", "caps=custom_dns")
		return "GETCONF:" + strings.Join(fields, "|"), "extended_legacy"
	}
	return "GETCONF:" + strings.Join(fields, "|"), "legacy"
}

func buildJSONConfigRequestPayload(localPort, deviceID, password, dnsOverride string) string {
	req := map[string]interface{}{
		"type":         "get_config",
		"protocol":     2,
		"local_port":   localPort,
		"device_id":    deviceID,
		"password":     password,
		"capabilities": []string{"custom_dns", "wireguard_config"},
	}
	if dns := splitDNSValues(dnsOverride); len(dns) > 0 {
		req["dns"] = dns
	}
	payload, err := json.Marshal(req)
	if err != nil {
		return "{}"
	}
	return string(payload)
}

func parseConfigResponse(resp string) (string, error) {
	resp = strings.TrimSpace(resp)
	if resp == "NOCONF" {
		return "", nil
	}
	if strings.HasPrefix(resp, "DENIED:") {
		return "", deniedConfigError(strings.TrimPrefix(resp, "DENIED:"))
	}
	if strings.HasPrefix(resp, "{") {
		var parsed configResponse
		if err := json.Unmarshal([]byte(resp), &parsed); err == nil {
			switch parsed.Type {
			case "config":
				return parsed.Config, nil
			case "no_config":
				return "", nil
			case "error":
				if parsed.Error == "denied" {
					return "", deniedConfigError(parsed.Reason)
				}
				return "", fmt.Errorf("ответ сервера: %s", parsed.Error)
			}
		}
	}
	return resp, nil
}

func describeConfigProtocol(requestMode, resp, config, dnsOverride string) string {
	response := "raw_config"
	protocol := "legacy"
	jsonResponse := false
	caps := "unconfirmed"

	trimmed := strings.TrimSpace(resp)
	if trimmed == "NOCONF" {
		response = "no_config"
	} else if strings.HasPrefix(trimmed, "DENIED:") {
		response = "denied"
	} else if strings.HasPrefix(trimmed, "{") {
		var parsed configResponse
		if err := json.Unmarshal([]byte(trimmed), &parsed); err == nil {
			jsonResponse = true
			response = parsed.Type
			if parsed.Protocol > 0 {
				protocol = fmt.Sprintf("v%d", parsed.Protocol)
			} else {
				protocol = "json"
			}
			if len(parsed.Capabilities) > 0 {
				caps = strings.Join(parsed.Capabilities, ",")
			}
			if parsed.Policy.MaxWorkers > 0 {
				caps = fmt.Sprintf("%s policy=max_workers:%d,custom_dns:%t", caps, parsed.Policy.MaxWorkers, parsed.Policy.CustomDNSAllowed)
			}
		}
	}

	return fmt.Sprintf(
		"request=%s response=%s protocol=%s json=%t dns=%s caps=%s",
		requestMode,
		response,
		protocol,
		jsonResponse,
		describeConfigDNS(config, dnsOverride),
		caps,
	)
}

func describeConfigDNS(config, dnsOverride string) string {
	requested := splitDNSValues(dnsOverride)
	if len(requested) == 0 {
		return "not_requested"
	}
	configured := splitDNSValues(extractConfigDNS(config))
	if len(configured) == 0 {
		return "missing"
	}
	for _, req := range requested {
		found := false
		for _, got := range configured {
			if req == got {
				found = true
				break
			}
		}
		if !found {
			return "not_in_config"
		}
	}
	return "matches_config"
}

func extractConfigDNS(config string) string {
	for _, line := range strings.Split(config, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(strings.ToLower(line), "dns") {
			if _, value, ok := strings.Cut(line, "="); ok {
				return value
			}
		}
	}
	return ""
}

func splitDNSValues(raw string) []string {
	parts := strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\t' || r == '\n' || r == '\r'
	})
	values := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			values = append(values, part)
		}
	}
	return values
}

func deniedConfigError(reason string) error {
	switch reason {
	case "wrong_password":
		return fmt.Errorf("FATAL_AUTH: неверный пароль подключения")
	case "expired":
		return fmt.Errorf("FATAL_AUTH: срок действия пароля истёк")
	case "device_mismatch":
		return fmt.Errorf("FATAL_AUTH: пароль привязан к другому устройству")
	default:
		return fmt.Errorf("FATAL_AUTH: доступ запрещён (%s)", reason)
	}
}
