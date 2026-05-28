package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "modernc.org/sqlite"
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
	db           *Database
	dbMutex      sync.Mutex
	dbFile       string
	legacyDBFile string
	sqliteDB     *sql.DB
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
	if err := os.MkdirAll(dir, 0700); err != nil {
		log.Fatalf("[DB] mkdir %s: %v", dir, err)
	}

	legacyDBFile = filepath.Join(dir, "passwords.json")
	dbFile = filepath.Join(dir, "server.db")

	var err error
	sqliteDB, err = sql.Open("sqlite", dbFile)
	if err != nil {
		log.Fatalf("[DB] SQLite open: %v", err)
	}
	sqliteDB.SetMaxOpenConns(1)
	sqliteDB.SetMaxIdleConns(1)

	if err := initSQLiteSchema(sqliteDB); err != nil {
		log.Fatalf("[DB] SQLite schema: %v", err)
	}

	if err := importLegacyJSONIfNeeded(sqliteDB, legacyDBFile); err != nil {
		log.Fatalf("[DB] Legacy import: %v", err)
	}

	loaded, err := loadDBFromSQLite(sqliteDB)
	if err != nil {
		log.Fatalf("[DB] SQLite load: %v", err)
	}

	db = loaded
	db.MainPassword = mainPass
	db.AdminID = adminID
	db.BotToken = botToken
	saveDB()
	log.Printf("[DB] SQLite storage: %s", dbFile)
}

func saveDB() {
	if sqliteDB == nil || db == nil {
		return
	}
	if err := saveDBToSQLite(sqliteDB, db); err != nil {
		log.Printf("[DB] save failed: %v", err)
	}
	if err := refreshWrapKeysFromDBLocked(); err != nil {
		log.Printf("[WRAP] refresh keys failed: %v", err)
	}
}

func closeDB() {
	if sqliteDB == nil {
		return
	}
	if err := sqliteDB.Close(); err != nil {
		log.Printf("[DB] close failed: %v", err)
	}
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

func initSQLiteSchema(conn *sql.DB) error {
	pragmas := []string{
		"PRAGMA journal_mode=WAL",
		"PRAGMA synchronous=NORMAL",
		"PRAGMA busy_timeout=5000",
		"PRAGMA foreign_keys=ON",
	}
	for _, pragma := range pragmas {
		if _, err := conn.Exec(pragma); err != nil {
			return fmt.Errorf("%s: %w", pragma, err)
		}
	}

	stmts := []string{
		`CREATE TABLE IF NOT EXISTS metadata (
			key TEXT PRIMARY KEY,
			value TEXT NOT NULL DEFAULT ''
		)`,
		`CREATE TABLE IF NOT EXISTS devices (
			device_id TEXT PRIMARY KEY,
			ip TEXT NOT NULL UNIQUE,
			priv_key TEXT NOT NULL,
			pub_key TEXT NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS passwords (
			password TEXT PRIMARY KEY,
			device_id TEXT NOT NULL DEFAULT '',
			expires_at INTEGER NOT NULL DEFAULT 0,
			down_bytes INTEGER NOT NULL DEFAULT 0,
			up_bytes INTEGER NOT NULL DEFAULT 0
		)`,
		`CREATE INDEX IF NOT EXISTS idx_passwords_device_id ON passwords(device_id)`,
		`PRAGMA user_version=1`,
	}
	for _, stmt := range stmts {
		if _, err := conn.Exec(stmt); err != nil {
			return err
		}
	}
	return nil
}

func importLegacyJSONIfNeeded(conn *sql.DB, legacyPath string) error {
	if legacyPath == "" {
		return nil
	}
	if _, err := os.Stat(legacyPath); err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	var count int
	if err := conn.QueryRow(`SELECT
		(SELECT COUNT(*) FROM passwords) + (SELECT COUNT(*) FROM devices)`).Scan(&count); err != nil {
		return err
	}
	if count > 0 {
		return nil
	}

	legacy, err := loadLegacyJSON(legacyPath)
	if err != nil {
		return err
	}
	if len(legacy.Passwords) == 0 && len(legacy.Devices) == 0 {
		return nil
	}
	if err := saveDBToSQLite(conn, legacy); err != nil {
		return err
	}
	log.Printf("[DB] Imported legacy JSON: %s", legacyPath)
	return nil
}

func loadLegacyJSON(path string) (*Database, error) {
	legacy := &Database{
		Passwords: make(map[string]*PasswordEntry),
		Devices:   make(map[string]*ClientDevice),
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, legacy); err != nil {
		return nil, err
	}
	ensureDatabaseMaps(legacy)
	return legacy, nil
}

func loadDBFromSQLite(conn *sql.DB) (*Database, error) {
	loaded := &Database{
		Passwords: make(map[string]*PasswordEntry),
		Devices:   make(map[string]*ClientDevice),
	}

	rows, err := conn.Query(`SELECT key, value FROM metadata`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var key, value string
		if err := rows.Scan(&key, &value); err != nil {
			rows.Close()
			return nil, err
		}
		switch key {
		case "main_password":
			loaded.MainPassword = value
		case "admin_id":
			loaded.AdminID = value
		case "bot_token":
			loaded.BotToken = value
		}
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}

	rows, err = conn.Query(`SELECT device_id, ip, priv_key, pub_key FROM devices`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		dev := &ClientDevice{}
		if err := rows.Scan(&dev.DeviceID, &dev.IP, &dev.PrivKey, &dev.PubKey); err != nil {
			rows.Close()
			return nil, err
		}
		loaded.Devices[dev.DeviceID] = dev
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}

	rows, err = conn.Query(`SELECT password, device_id, expires_at, down_bytes, up_bytes FROM passwords`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var password string
		entry := &PasswordEntry{}
		if err := rows.Scan(&password, &entry.DeviceID, &entry.ExpiresAt, &entry.DownBytes, &entry.UpBytes); err != nil {
			rows.Close()
			return nil, err
		}
		loaded.Passwords[password] = entry
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}

	return loaded, nil
}

func saveDBToSQLite(conn *sql.DB, snapshot *Database) error {
	ensureDatabaseMaps(snapshot)

	tx, err := conn.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.Exec(`DELETE FROM metadata`); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM passwords`); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM devices`); err != nil {
		return err
	}

	metaStmt, err := tx.Prepare(`INSERT INTO metadata(key, value) VALUES(?, ?)`)
	if err != nil {
		return err
	}
	for _, item := range []struct {
		key   string
		value string
	}{
		{"main_password", snapshot.MainPassword},
		{"admin_id", snapshot.AdminID},
		{"bot_token", snapshot.BotToken},
	} {
		if _, err := metaStmt.Exec(item.key, item.value); err != nil {
			metaStmt.Close()
			return err
		}
	}
	if err := metaStmt.Close(); err != nil {
		return err
	}

	devStmt, err := tx.Prepare(`INSERT INTO devices(device_id, ip, priv_key, pub_key) VALUES(?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	for _, dev := range snapshot.Devices {
		if dev == nil || dev.DeviceID == "" {
			continue
		}
		if _, err := devStmt.Exec(dev.DeviceID, dev.IP, dev.PrivKey, dev.PubKey); err != nil {
			devStmt.Close()
			return err
		}
	}
	if err := devStmt.Close(); err != nil {
		return err
	}

	passStmt, err := tx.Prepare(`INSERT INTO passwords(password, device_id, expires_at, down_bytes, up_bytes) VALUES(?, ?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	for password, entry := range snapshot.Passwords {
		if entry == nil || password == "" {
			continue
		}
		deviceID := entry.DeviceID
		if deviceID != "" {
			if _, exists := snapshot.Devices[deviceID]; !exists {
				deviceID = ""
			}
		}
		if _, err := passStmt.Exec(password, deviceID, entry.ExpiresAt, entry.DownBytes, entry.UpBytes); err != nil {
			passStmt.Close()
			return err
		}
	}
	if err := passStmt.Close(); err != nil {
		return err
	}

	return tx.Commit()
}

func ensureDatabaseMaps(snapshot *Database) {
	if snapshot.Passwords == nil {
		snapshot.Passwords = make(map[string]*PasswordEntry)
	}
	if snapshot.Devices == nil {
		snapshot.Devices = make(map[string]*ClientDevice)
	}
}
