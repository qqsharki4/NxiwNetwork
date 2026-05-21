package main

import (
	"encoding/json"
	"strconv"
	"strings"
)

const (
	nxiwProtocolVersion = 2
	defaultClientPort   = "9000"

	protocolFeatureCustomDNS       = "custom_dns"
	protocolFeatureWireGuardConfig = "wireguard_config"
)

type protocolResponseMode int

const (
	protocolResponseLegacy protocolResponseMode = iota
	protocolResponseJSON
)

type configRequest struct {
	LocalPort    string
	DeviceID     string
	Password     string
	DNS          string
	Protocol     int
	ResponseMode protocolResponseMode
	Capabilities map[string]bool
}

type protocolHello struct {
	Type         string   `json:"type"`
	ProtocolMin  int      `json:"protocol_min,omitempty"`
	ProtocolMax  int      `json:"protocol_max,omitempty"`
	AppVersion   string   `json:"app_version,omitempty"`
	Capabilities []string `json:"capabilities,omitempty"`
}

type protocolHelloOK struct {
	Type         string   `json:"type"`
	Protocol     int      `json:"protocol"`
	Capabilities []string `json:"capabilities"`
}

type protocolConfigRequest struct {
	Type         string          `json:"type"`
	Protocol     int             `json:"protocol,omitempty"`
	LocalPort    string          `json:"local_port,omitempty"`
	DeviceID     string          `json:"device_id,omitempty"`
	Password     string          `json:"password,omitempty"`
	DNS          json.RawMessage `json:"dns,omitempty"`
	Capabilities []string        `json:"capabilities,omitempty"`
}

type protocolConfigResponse struct {
	Type         string   `json:"type"`
	Protocol     int      `json:"protocol"`
	Config       string   `json:"config,omitempty"`
	Error        string   `json:"error,omitempty"`
	Reason       string   `json:"reason,omitempty"`
	Capabilities []string `json:"capabilities,omitempty"`
}

func parseProtocolHello(packet []byte) (protocolHello, bool) {
	var msg protocolHello
	if !looksLikeJSON(packet) {
		return msg, false
	}
	if err := json.Unmarshal(packet, &msg); err != nil {
		return msg, false
	}
	return msg, msg.Type == "hello"
}

func buildProtocolHelloOK() []byte {
	payload, err := json.Marshal(protocolHelloOK{
		Type:     "hello_ok",
		Protocol: nxiwProtocolVersion,
		Capabilities: []string{
			protocolFeatureCustomDNS,
			protocolFeatureWireGuardConfig,
		},
	})
	if err != nil {
		return []byte(`{"type":"hello_ok","protocol":2,"capabilities":["custom_dns","wireguard_config"]}`)
	}
	return payload
}

func parseConfigRequest(packet []byte) (configRequest, bool) {
	if req, ok := parseLegacyConfigRequest(packet); ok {
		return req, true
	}
	if req, ok := parseJSONConfigRequest(packet); ok {
		return req, true
	}
	return configRequest{}, false
}

func parseLegacyConfigRequest(packet []byte) (configRequest, bool) {
	raw := strings.TrimSpace(string(packet))
	if !strings.HasPrefix(raw, "GETCONF:") {
		return configRequest{}, false
	}
	parts := strings.Split(strings.TrimSpace(strings.TrimPrefix(raw, "GETCONF:")), "|")
	req := configRequest{
		LocalPort:    defaultClientPort,
		DeviceID:     "unknown",
		Protocol:     1,
		ResponseMode: protocolResponseLegacy,
		Capabilities: make(map[string]bool),
	}
	if len(parts) > 0 {
		req.LocalPort = normalizeClientPort(parts[0])
	}
	if len(parts) > 1 && strings.TrimSpace(parts[1]) != "" {
		req.DeviceID = strings.TrimSpace(parts[1])
	}
	if len(parts) > 2 {
		req.Password = strings.TrimSpace(parts[2])
	}
	if len(parts) > 3 {
		req.DNS = strings.TrimSpace(parts[3])
	}
	if len(parts) > 4 {
		for _, field := range parts[4:] {
			key, value, ok := strings.Cut(strings.TrimSpace(field), "=")
			if !ok {
				continue
			}
			switch strings.ToLower(strings.TrimSpace(key)) {
			case "proto", "protocol":
				if parsed, err := strconv.Atoi(strings.TrimSpace(value)); err == nil && parsed > 0 {
					req.Protocol = parsed
				}
			case "caps", "capabilities":
				addCapabilities(req.Capabilities, value)
			case "dns":
				if req.DNS == "" {
					req.DNS = strings.TrimSpace(value)
				}
			}
		}
	}
	return req, true
}

func parseJSONConfigRequest(packet []byte) (configRequest, bool) {
	if !looksLikeJSON(packet) {
		return configRequest{}, false
	}
	var msg protocolConfigRequest
	if err := json.Unmarshal(packet, &msg); err != nil || msg.Type != "get_config" {
		return configRequest{}, false
	}
	req := configRequest{
		LocalPort:    strings.TrimSpace(msg.LocalPort),
		DeviceID:     strings.TrimSpace(msg.DeviceID),
		Password:     strings.TrimSpace(msg.Password),
		DNS:          decodeProtocolDNS(msg.DNS),
		Protocol:     msg.Protocol,
		ResponseMode: protocolResponseJSON,
		Capabilities: make(map[string]bool),
	}
	if req.LocalPort == "" {
		req.LocalPort = defaultClientPort
	} else {
		req.LocalPort = normalizeClientPort(req.LocalPort)
	}
	if req.DeviceID == "" {
		req.DeviceID = "unknown"
	}
	if req.Protocol <= 0 {
		req.Protocol = nxiwProtocolVersion
	}
	for _, capability := range msg.Capabilities {
		capability = strings.TrimSpace(capability)
		if capability != "" {
			req.Capabilities[capability] = true
		}
	}
	return req, true
}

func buildConfigResponse(req configRequest, config string) []byte {
	if req.ResponseMode == protocolResponseJSON {
		return marshalProtocolResponse(protocolConfigResponse{
			Type:     "config",
			Protocol: responseProtocol(req),
			Config:   config,
			Capabilities: []string{
				protocolFeatureCustomDNS,
				protocolFeatureWireGuardConfig,
			},
		}, []byte("NOCONF"))
	}
	return []byte(config)
}

func buildNoConfigResponse(req configRequest) []byte {
	if req.ResponseMode == protocolResponseJSON {
		return marshalProtocolResponse(protocolConfigResponse{
			Type:     "no_config",
			Protocol: responseProtocol(req),
		}, []byte("NOCONF"))
	}
	return []byte("NOCONF")
}

func buildDeniedResponse(req configRequest, reason string) []byte {
	if req.ResponseMode == protocolResponseJSON {
		return marshalProtocolResponse(protocolConfigResponse{
			Type:     "error",
			Protocol: responseProtocol(req),
			Error:    "denied",
			Reason:   reason,
		}, []byte("DENIED:"+reason))
	}
	return []byte("DENIED:" + reason)
}

func responseProtocol(req configRequest) int {
	if req.Protocol > 0 {
		return req.Protocol
	}
	return nxiwProtocolVersion
}

func marshalProtocolResponse(resp protocolConfigResponse, fallback []byte) []byte {
	payload, err := json.Marshal(resp)
	if err != nil {
		return fallback
	}
	return payload
}

func decodeProtocolDNS(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var list []string
	if err := json.Unmarshal(raw, &list); err == nil {
		return strings.Join(list, ",")
	}
	var single string
	if err := json.Unmarshal(raw, &single); err == nil {
		return strings.TrimSpace(single)
	}
	return ""
}

func normalizeClientPort(raw string) string {
	port, err := strconv.Atoi(strings.TrimSpace(raw))
	if err != nil || port < 1 || port > 65535 {
		return defaultClientPort
	}
	return strconv.Itoa(port)
}

func looksLikeJSON(packet []byte) bool {
	trimmed := strings.TrimSpace(string(packet))
	return strings.HasPrefix(trimmed, "{") && strings.HasSuffix(trimmed, "}")
}

func addCapabilities(dst map[string]bool, raw string) {
	for _, capability := range strings.Split(raw, ",") {
		capability = strings.TrimSpace(capability)
		if capability != "" {
			dst[capability] = true
		}
	}
}
