package main

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type ClientDevice struct {
	DeviceID string `json:"device_id"`
	IP       string `json:"ip"`
	PrivKey  string `json:"priv_key"`
	PubKey   string `json:"pub_key"`
}

type PasswordEntry struct {
	DeviceID  string `json:"device_id"`  // пусто = ещё не привязан
	ExpiresAt int64  `json:"expires_at"` // unix timestamp
	DownBytes int64  `json:"down_bytes"` // скачано клиентом
	UpBytes   int64  `json:"up_bytes"`   // отдано клиентом
}

type Database struct {
	MainPassword string                    `json:"main_password"`
	AdminID      string                    `json:"admin_id"`
	BotToken     string                    `json:"bot_token"`
	Passwords    map[string]*PasswordEntry `json:"passwords"`
	Devices      map[string]*ClientDevice  `json:"devices"`
}

var (
	db      *Database
	dbMutex sync.Mutex
	dbFile  string
)

const passChars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"

func generatePassword() string {
	b := make([]byte, 7)
	randomBytes := make([]byte, len(b))
	if _, err := rand.Read(randomBytes); err != nil {
		now := time.Now().UnixNano()
		for i := range b {
			b[i] = passChars[int(now+int64(i))%len(passChars)]
		}
		return string(b)
	}
	for i, raw := range randomBytes {
		b[i] = passChars[int(raw)%len(passChars)]
	}
	return string(b)
}

func initDB(dir, mainPass, adminID, botToken string) {
	dbFile = filepath.Join(dir, "passwords.json")
	db = &Database{
		Passwords: make(map[string]*PasswordEntry),
		Devices:   make(map[string]*ClientDevice),
	}
	data, err := os.ReadFile(dbFile)
	if err == nil {
		json.Unmarshal(data, db)
	}
	if db.Passwords == nil {
		db.Passwords = make(map[string]*PasswordEntry)
	}
	if db.Devices == nil {
		db.Devices = make(map[string]*ClientDevice)
	}
	db.MainPassword = mainPass
	db.AdminID = adminID
	db.BotToken = botToken
	saveDB()
}

func saveDB() {
	data, _ := json.MarshalIndent(db, "", "  ")
	os.WriteFile(dbFile, data, 0600)
}

func isPasswordExpired(entry *PasswordEntry) bool {
	if entry.ExpiresAt == 0 {
		return false // бессрочный
	}
	return time.Now().Unix() > entry.ExpiresAt
}

func getNextIP() string {
	used := make(map[string]bool)
	for _, dev := range db.Devices {
		used[dev.IP] = true
	}
	for i := 2; i <= 250; i++ {
		ip := fmt.Sprintf("10.66.66.%d", i)
		if !used[ip] {
			return ip
		}
	}
	return ""
}
