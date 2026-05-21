package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"golang.zx2c4.com/wireguard/device"
)

func botLoop(token string, adminIDstr string, wgDev *device.Device) {
	if token == "" || adminIDstr == "" {
		return
	}
	adminID, _ := strconv.ParseInt(adminIDstr, 10, 64)
	if adminID == 0 {
		return
	}

	// Устанавливаем команды для синей кнопки Menu
	go func() {
		cmds := `{"commands":[{"command":"new","description":"Создать временный пароль"},{"command":"list","description":"Управление доступами"}]}`
		resp, err := http.Post(fmt.Sprintf("https://api.telegram.org/bot%s/setMyCommands", token), "application/json", strings.NewReader(cmds))
		if err == nil {
			resp.Body.Close()
		}
	}()

	offset := 0
	client := &http.Client{Timeout: 65 * time.Second}

	// Состояние ожидания ввода дней
	var waitingForDays bool

	for {
		url := fmt.Sprintf("https://api.telegram.org/bot%s/getUpdates?timeout=60&offset=%d", token, offset)
		resp, err := client.Get(url)
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}

		var res struct {
			Ok     bool `json:"ok"`
			Result []struct {
				UpdateID int `json:"update_id"`
				Message  *struct {
					Chat struct {
						ID int64 `json:"id"`
					} `json:"chat"`
					Text string `json:"text"`
				} `json:"message"`
				CallbackQuery *struct {
					ID      string `json:"id"`
					Data    string `json:"data"`
					Message struct {
						MessageID int `json:"message_id"`
						Chat      struct {
							ID int64 `json:"id"`
						} `json:"chat"`
					} `json:"message"`
				} `json:"callback_query"`
			} `json:"result"`
		}

		err = json.NewDecoder(resp.Body).Decode(&res)
		resp.Body.Close()
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}

		for _, u := range res.Result {
			offset = u.UpdateID + 1

			// ═══ Callback кнопки ═══
			if u.CallbackQuery != nil && u.CallbackQuery.Message.Chat.ID == adminID {
				data := u.CallbackQuery.Data
				answerCallback(token, u.CallbackQuery.ID)

				if strings.HasPrefix(data, "viewpass_") {
					// Просмотр деталей пароля
					pass := strings.TrimPrefix(data, "viewpass_")
					dbMutex.Lock()
					entry, exists := db.Passwords[pass]
					if !exists {
						dbMutex.Unlock()
						sendTelegram(token, adminID, "❌ Пароль не найден", nil)
						continue
					}
					txt := fmt.Sprintf("🔑 *Пароль:* `%s`\n", pass)
					if entry.ExpiresAt > 0 {
						expireTime := time.Unix(entry.ExpiresAt, 0)
						remaining := time.Until(expireTime)
						if remaining > 0 {
							txt += fmt.Sprintf("⏰ Истекает: %s (через %dd)\n", expireTime.Format("02.01.2006"), int(remaining.Hours()/24))
						} else {
							txt += "⏰ *ИСТЁК* ❌\n"
						}
					} else {
						txt += "⏰ Бессрочный ♾\n"
					}
					txt += "\n📱 *Привязанное устройство:*\n"
					var kb []map[string]interface{}
					if entry.DeviceID == "" {
						txt += "_Ожидает первого подключения..._\n"
					} else {
						dev, devExists := db.Devices[entry.DeviceID]
						if devExists {
							txt += fmt.Sprintf("• ID: `%s`\n• IP: `%s`\n", entry.DeviceID, dev.IP)
						} else {
							txt += fmt.Sprintf("• ID: `%s` (устройство удалено)\n", entry.DeviceID)
						}
						kb = append(kb, map[string]interface{}{
							"text":          "🗑 Отвязать устройство",
							"callback_data": "unbind_" + pass,
						})
					}
					dbMutex.Unlock()
					kb = append(kb, map[string]interface{}{
						"text":          "❌ Удалить пароль",
						"callback_data": "delpass_" + pass,
					})
					kb = append(kb, map[string]interface{}{
						"text":          "◀️ Назад к списку",
						"callback_data": "backlist",
					})
					var keyboard [][]map[string]interface{}
					for _, btn := range kb {
						keyboard = append(keyboard, []map[string]interface{}{btn})
					}
					sendTelegram(token, adminID, txt, map[string]interface{}{"inline_keyboard": keyboard})

				} else if strings.HasPrefix(data, "unbind_") {
					pass := strings.TrimPrefix(data, "unbind_")
					dbMutex.Lock()
					entry, exists := db.Passwords[pass]
					if exists && entry.DeviceID != "" {
						// Удаляем устройство из WG и из хранилища
						dev, devExists := db.Devices[entry.DeviceID]
						if devExists {
							pubHex, _ := b64ToHex(dev.PubKey)
							wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
							delete(db.Devices, entry.DeviceID)
						}
						entry.DeviceID = ""
						saveDB()
					}
					dbMutex.Unlock()
					sendTelegram(token, adminID, fmt.Sprintf("✅ Устройство отвязано от пароля `%s`", pass), nil)

				} else if strings.HasPrefix(data, "delpass_") {
					pass := strings.TrimPrefix(data, "delpass_")
					dbMutex.Lock()
					entry, exists := db.Passwords[pass]
					if exists && entry.DeviceID != "" {
						dev, devExists := db.Devices[entry.DeviceID]
						if devExists {
							pubHex, _ := b64ToHex(dev.PubKey)
							wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
							delete(db.Devices, entry.DeviceID)
						}
					}
					delete(db.Passwords, pass)
					saveDB()
					dbMutex.Unlock()
					sendTelegram(token, adminID, fmt.Sprintf("✅ Пароль `%s` и его устройство удалены", pass), nil)

				} else if strings.HasPrefix(data, "deldev_") {
					devID := strings.TrimPrefix(data, "deldev_")
					dbMutex.Lock()
					dev, exists := db.Devices[devID]
					if exists {
						delete(db.Devices, devID)
						pubHex, _ := b64ToHex(dev.PubKey)
						wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
						// Очищаем привязку из пароля
						for _, entry := range db.Passwords {
							if entry.DeviceID == devID {
								entry.DeviceID = ""
							}
						}
						saveDB()
					}
					dbMutex.Unlock()
					sendTelegram(token, adminID, fmt.Sprintf("✅ Устройство `%s` удалено", devID), nil)

				} else if data == "backlist" {
					sendPasswordList(token, adminID)
				}
			}

			// ═══ Текстовые команды ═══
			msg := u.Message
			if msg == nil || msg.Chat.ID != adminID {
				continue
			}

			cmd := strings.TrimSpace(msg.Text)

			// Обработка ввода количества дней
			if waitingForDays {
				waitingForDays = false
				days, parseErr := strconv.Atoi(cmd)
				if parseErr != nil || days < 1 || days > 365 {
					sendTelegram(token, adminID, "❌ Неверное значение. Укажите число от 1 до 365, или отправьте /new заново.", nil)
					continue
				}
				newPass := generatePassword()
				expiresAt := time.Now().Add(time.Duration(days) * 24 * time.Hour).Unix()
				dbMutex.Lock()
				db.Passwords[newPass] = &PasswordEntry{ExpiresAt: expiresAt}
				saveDB()
				dbMutex.Unlock()
				expDate := time.Unix(expiresAt, 0).Format("02.01.2006")
				sendTelegram(token, adminID, fmt.Sprintf("🔑 Новый пароль:\n`%s`\n\n⏰ Действует %d дн. (до %s)\n📱 Ожидает первого подключения", newPass, days, expDate), nil)
				continue
			}

			if cmd == "/start" || cmd == "/help" {
				sendTelegram(token, adminID, "🤖 *NxiwNetwork VPN Manager*\n\n/new — Создать пароль\n/list — Список паролей", nil)

			} else if cmd == "/new" {
				waitingForDays = true
				sendTelegram(token, adminID, "📅 Введите срок действия пароля в днях (1–365):\n\n_Примеры: 30 = месяц, 365 = год_", nil)

			} else if cmd == "/list" {
				sendPasswordList(token, adminID)
			}
		}
	}
}

func sendPasswordList(token string, adminID int64) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	// Очистка истёкших
	for p, entry := range db.Passwords {
		if isPasswordExpired(entry) {
			// Удаляем привязанное устройство
			if entry.DeviceID != "" {
				delete(db.Devices, entry.DeviceID)
			}
			delete(db.Passwords, p)
		}
	}
	saveDB()

	txt := "🔐 *Пароли:*\n\n"
	txt += fmt.Sprintf("🔒 Главный: `%s` (владелец)\n\n", db.MainPassword)

	var inlineKb []map[string]interface{}

	if len(db.Passwords) == 0 {
		txt += "_Нет сгенерированных паролей._\n"
	} else {
		for p, entry := range db.Passwords {
			status := "🟢"
			if entry.DeviceID != "" {
				status = "🔗"
			}
			expiry := "♾"
			if entry.ExpiresAt > 0 {
				remaining := time.Until(time.Unix(entry.ExpiresAt, 0))
				if remaining > 0 {
					expiry = fmt.Sprintf("%dd", int(remaining.Hours()/24)+1)
				} else {
					expiry = "❌"
				}
			}
			txt += fmt.Sprintf("%s `%s` (%s)\n", status, p, expiry)
			inlineKb = append(inlineKb, map[string]interface{}{
				"text":          "🔍 " + p,
				"callback_data": "viewpass_" + p,
			})
		}
	}

	txt += "\n🟢 = свободен | 🔗 = привязан"

	var replyMarkup interface{}
	if len(inlineKb) > 0 {
		var keyboard [][]map[string]interface{}
		for _, btn := range inlineKb {
			keyboard = append(keyboard, []map[string]interface{}{btn})
		}
		replyMarkup = map[string]interface{}{"inline_keyboard": keyboard}
	}
	sendTelegram(token, adminID, txt, replyMarkup)
}

func answerCallback(token, callbackID string) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/answerCallbackQuery", token)
	payload := map[string]interface{}{"callback_query_id": callbackID}
	body, _ := json.Marshal(payload)
	http.Post(url, "application/json", bytes.NewBuffer(body))
}

func maskPassword(pass string) string {
	if len(pass) <= 3 {
		return pass
	}
	return pass[:3] + "****"
}

func sendTelegram(token string, chatID int64, text string, replyMarkup interface{}) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendMessage", token)
	payload := map[string]interface{}{
		"chat_id":    chatID,
		"text":       text,
		"parse_mode": "Markdown",
	}
	if replyMarkup != nil {
		payload["reply_markup"] = replyMarkup
	}
	body, _ := json.Marshal(payload)
	http.Post(url, "application/json", bytes.NewBuffer(body))
}
