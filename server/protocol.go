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
	protocolFeatureCustomMTU       = "custom_mtu"
	protocolFeatureNodePolicy      = "node_policy"
	protocolFeatureWireGuardConfig = "wireguard_config"
	protocolMaxWorkers             = 72
	protocolMinMTU                 = 1280
	protocolMaxMTU                 = 1500
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
	MTU          int
	Protocol     int
	ResponseMode protocolResponseMode
	Capabilities map[string]bool
}

type protocolHello struct {
	Type         string         `json:"type"`
	Proto        []int          `json:"proto,omitempty"`
	ProtocolMin  int            `json:"protocol_min,omitempty"`
	ProtocolMax  int            `json:"protocol_max,omitempty"`
	AppVersion   string         `json:"app_version,omitempty"`
	Features     map[string]int `json:"features,omitempty"`
	Capabilities []string       `json:"capabilities,omitempty"`
}

type protocolHelloOK struct {
	Type     string           `json:"type"`
	Proto    int              `json:"proto"`
	Features protocolFeatures `json:"features"`
	Policy   protocolPolicy   `json:"policy"`
}

type protocolConfigRequest struct {
	Type         string                `json:"type"`
	Proto        int                   `json:"proto,omitempty"`
	Protocol     int                   `json:"protocol,omitempty"`
	LocalPort    string                `json:"local_port,omitempty"`
	DeviceID     string                `json:"device_id,omitempty"`
	Password     string                `json:"password,omitempty"`
	DNS          json.RawMessage       `json:"dns,omitempty"`
	MTU          int                   `json:"mtu,omitempty"`
	CustomDNS    json.RawMessage       `json:"custom_dns,omitempty"`
	CustomMTU    int                   `json:"custom_mtu,omitempty"`
	Options      protocolConfigOptions `json:"options,omitempty"`
	Features     map[string]int        `json:"features,omitempty"`
	Capabilities []string              `json:"capabilities,omitempty"`
}

type protocolConfigResponse struct {
	Type     string           `json:"type"`
	Proto    int              `json:"proto"`
	Config   string           `json:"config,omitempty"`
	WG       string           `json:"wg,omitempty"`
	Error    string           `json:"error,omitempty"`
	Reason   string           `json:"reason,omitempty"`
	Features protocolFeatures `json:"features,omitempty"`
	Policy   protocolPolicy   `json:"policy,omitempty"`
	Applied  *protocolApplied `json:"applied,omitempty"`
}

type protocolFeatures map[string]int

type protocolConfigOptions struct {
	DNS json.RawMessage `json:"dns,omitempty"`
	MTU int             `json:"mtu,omitempty"`
}

type protocolApplied struct {
	DNS string `json:"dns,omitempty"`
	MTU int    `json:"mtu,omitempty"`
}

type protocolPolicy struct {
	Default protocolPolicyDefault `json:"default"`
	Allow   protocolPolicyAllow   `json:"allow"`
	Limits  protocolPolicyLimits  `json:"limits"`
}

type protocolPolicyDefault struct {
	DNS string `json:"dns"`
	MTU int    `json:"mtu"`
}

type protocolPolicyAllow struct {
	CustomDNS bool `json:"custom_dns"`
	CustomMTU bool `json:"custom_mtu"`
}

type protocolPolicyLimits struct {
	Workers int    `json:"workers"`
	MTU     [2]int `json:"mtu"`
}

func parseProtocolHello(packet []byte) (protocolHello, bool) {
	var msg protocolHello
	if !looksLikeJSON(packet) {
		return msg, false
	}
	if err := json.Unmarshal(packet, &msg); err != nil {
		return msg, false
	}
	return msg, msg.Type == "hello" && helloSupportsProtocol(msg)
}

func buildProtocolHelloOK() []byte {
	payload, err := json.Marshal(protocolHelloOK{
		Type:     "hello_ok",
		Proto:    nxiwProtocolVersion,
		Features: buildProtocolFeatures(),
		Policy:   buildProtocolPolicy(),
	})
	if err != nil {
		return []byte(`{"type":"hello_ok","proto":2,"features":{"custom_dns":1,"custom_mtu":1,"node_policy":1},"policy":{"default":{"dns":"1.1.1.1","mtu":1280},"allow":{"custom_dns":true,"custom_mtu":true},"limits":{"workers":72,"mtu":[1280,1500]}}}`)
	}
	return payload
}

func helloSupportsProtocol(msg protocolHello) bool {
	if len(msg.Proto) >= 2 {
		return msg.Proto[0] <= nxiwProtocolVersion && msg.Proto[1] >= nxiwProtocolVersion
	}
	if msg.ProtocolMin > 0 || msg.ProtocolMax > 0 {
		maxProtocol := msg.ProtocolMax
		if maxProtocol <= 0 {
			maxProtocol = msg.ProtocolMin
		}
		minProtocol := msg.ProtocolMin
		if minProtocol <= 0 {
			minProtocol = maxProtocol
		}
		return minProtocol <= nxiwProtocolVersion && maxProtocol >= nxiwProtocolVersion
	}
	return true
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
			case "mtu", "custom_mtu":
				if parsed, err := strconv.Atoi(strings.TrimSpace(value)); err == nil {
					req.MTU = parsed
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
		DNS:          decodeProtocolDNS(msg.Options.DNS),
		MTU:          msg.Options.MTU,
		Protocol:     msg.Proto,
		ResponseMode: protocolResponseJSON,
		Capabilities: make(map[string]bool),
	}
	if req.DNS == "" {
		req.DNS = decodeProtocolDNS(msg.DNS)
	}
	if req.DNS == "" {
		req.DNS = decodeProtocolDNS(msg.CustomDNS)
	}
	if req.MTU == 0 {
		req.MTU = msg.MTU
	}
	if req.MTU == 0 {
		req.MTU = msg.CustomMTU
	}
	if req.Protocol <= 0 {
		req.Protocol = msg.Protocol
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
	for feature, version := range msg.Features {
		feature = strings.TrimSpace(feature)
		if feature != "" && version > 0 {
			req.Capabilities[feature] = true
		}
	}
	return req, true
}

func buildConfigResponse(req configRequest, config string, applied protocolApplied) []byte {
	if req.ResponseMode == protocolResponseJSON {
		return marshalProtocolResponse(protocolConfigResponse{
			Type:     "config",
			Proto:    responseProtocol(req),
			Config:   config,
			WG:       config,
			Features: buildProtocolFeatures(),
			Policy:   buildProtocolPolicy(),
			Applied:  &applied,
		}, []byte("NOCONF"))
	}
	return []byte(config)
}

func buildNoConfigResponse(req configRequest) []byte {
	if req.ResponseMode == protocolResponseJSON {
		return marshalProtocolResponse(protocolConfigResponse{
			Type:     "no_config",
			Proto:    responseProtocol(req),
			Features: buildProtocolFeatures(),
			Policy:   buildProtocolPolicy(),
		}, []byte("NOCONF"))
	}
	return []byte("NOCONF")
}

func buildDeniedResponse(req configRequest, reason string) []byte {
	if req.ResponseMode == protocolResponseJSON {
		return marshalProtocolResponse(protocolConfigResponse{
			Type:     "error",
			Proto:    responseProtocol(req),
			Error:    "denied",
			Reason:   reason,
			Features: buildProtocolFeatures(),
			Policy:   buildProtocolPolicy(),
		}, []byte("DENIED:"+reason))
	}
	return []byte("DENIED:" + reason)
}

func buildProtocolFeatures() protocolFeatures {
	return protocolFeatures{
		protocolFeatureCustomDNS:  1,
		protocolFeatureCustomMTU:  1,
		protocolFeatureNodePolicy: 1,
	}
}

func buildProtocolPolicy() protocolPolicy {
	return protocolPolicy{
		Default: protocolPolicyDefault{
			DNS: dns,
			MTU: wgMTU,
		},
		Allow: protocolPolicyAllow{
			CustomDNS: true,
			CustomMTU: true,
		},
		Limits: protocolPolicyLimits{
			Workers: protocolMaxWorkers,
			MTU:     [2]int{protocolMinMTU, protocolMaxMTU},
		},
	}
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

func normalizeClientMTU(raw int) int {
	if raw >= protocolMinMTU && raw <= protocolMaxMTU {
		return raw
	}
	return wgMTU
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
