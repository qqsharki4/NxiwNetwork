package main

import (
	"fmt"
	"log"

	"golang.zx2c4.com/wireguard/device"
)

type configRequestResult struct {
	Response   []byte
	Continue   bool
	DeviceID   string
	Password   string
	IsMainPass bool
}

func handleConfigRequest(req configRequest, wgDev *device.Device, keys *wgKeys) configRequestResult {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	isMainPass := req.Password != "" && req.Password == db.MainPassword
	entry, isGenPass := db.Passwords[req.Password]
	valid := isMainPass || (isGenPass && !isPasswordExpired(entry))

	if valid && isGenPass && entry.DeviceID != "" && entry.DeviceID != req.DeviceID {
		log.Printf("[WG] Отказ: пароль %s привязан к %s, запрос от %s", req.Password, entry.DeviceID, req.DeviceID)
		return configRequestResult{Response: buildDeniedResponse(req, "device_mismatch")}
	}

	if !valid {
		if isGenPass && isPasswordExpired(entry) {
			log.Printf("[WG] Отказ: пароль %s истёк, от %s", req.Password, req.DeviceID)
			return configRequestResult{Response: buildDeniedResponse(req, "expired")}
		}
		log.Printf("[WG] Отказ (неверный пароль) от %s", req.DeviceID)
		return configRequestResult{Response: buildDeniedResponse(req, "wrong_password")}
	}

	dev, err := ensureClientDevice(req.DeviceID, wgDev)
	if err != nil {
		log.Printf("[WG] Ошибка подготовки peer %s: %v", req.DeviceID, err)
		return configRequestResult{Response: buildNoConfigResponse(req)}
	}

	if isGenPass && entry.DeviceID == "" {
		entry.DeviceID = req.DeviceID
		saveDB()
		log.Printf("[WG] Пароль %s привязан к устройству %s", req.Password, req.DeviceID)
	}

	return configRequestResult{
		Response:   buildConfigResponse(req, buildClientConfig(keys.serverPublic, dev.PrivKey, dev.IP, req.LocalPort, req.DNS)),
		Continue:   true,
		DeviceID:   req.DeviceID,
		Password:   req.Password,
		IsMainPass: isMainPass,
	}
}

func ensureClientDevice(deviceID string, wgDev *device.Device) (*ClientDevice, error) {
	if dev, exists := db.Devices[deviceID]; exists {
		return dev, nil
	}

	dev := &ClientDevice{DeviceID: deviceID, IP: getNextIP()}
	privB64, pubB64, err := generateKeyPair()
	if err != nil || dev.IP == "" {
		if err != nil {
			return nil, err
		}
		return nil, fmt.Errorf("нет свободного IP для device %s", deviceID)
	}

	dev.PrivKey = privB64
	dev.PubKey = pubB64
	if err := applyClientPeer(wgDev, dev); err != nil {
		return nil, err
	}

	db.Devices[deviceID] = dev
	saveDB()
	log.Printf("[WG] Новое устройство %s (IP: %s)", deviceID, dev.IP)
	return dev, nil
}

func applyClientPeer(wgDev *device.Device, dev *ClientDevice) error {
	pubHex, err := b64ToHex(dev.PubKey)
	if err != nil {
		return fmt.Errorf("public key %s: %w", dev.DeviceID, err)
	}
	if err := wgDev.IpcSet(fmt.Sprintf("public_key=%s\nallowed_ip=%s/32\n", pubHex, dev.IP)); err != nil {
		return fmt.Errorf("IpcSet %s: %w", dev.DeviceID, err)
	}
	return nil
}
