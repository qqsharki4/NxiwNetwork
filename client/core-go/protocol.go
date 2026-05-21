package main

import (
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"time"
)

type configResponse struct {
	Type   string `json:"type"`
	Config string `json:"config"`
	Error  string `json:"error"`
	Reason string `json:"reason"`
}

// RequestConfig запрашивает WireGuard конфиг через DTLS-соединение.
func RequestConfig(conn net.Conn, localPort, deviceID, password, dnsOverride string) (string, error) {
	payload := buildConfigRequestPayload(localPort, deviceID, password, dnsOverride)
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

	return parseConfigResponse(string(b[:n]))
}

func buildConfigRequestPayload(localPort, deviceID, password, dnsOverride string) string {
	fields := []string{localPort, deviceID, password}
	dnsOverride = strings.TrimSpace(dnsOverride)
	if dnsOverride != "" {
		fields = append(fields, dnsOverride, "proto=2", "caps=custom_dns")
	}
	return "GETCONF:" + strings.Join(fields, "|")
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
