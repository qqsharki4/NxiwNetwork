package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"net/http"
	neturl "net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	fhttp "github.com/bogdanfinn/fhttp"
	tlsclient "github.com/bogdanfinn/tls-client"
	"github.com/bogdanfinn/tls-client/profiles"
	"github.com/google/uuid"
)

var vkSemaphore = make(chan struct{}, 2)

var (
	sharedTransportOnce sync.Once
	sharedTransport     *http.Transport
)

var noDnsFlag atomic.Bool

var (
	cachedSuccessToken string
	cachedTokenUsages  int32
	cacheMutex         sync.Mutex
)

func popCachedToken() string {
	cacheMutex.Lock()
	defer cacheMutex.Unlock()
	if cachedTokenUsages > 0 {
		cachedTokenUsages--
		return cachedSuccessToken
	}
	return ""
}

func pushCachedToken(token string, usages int32) {
	cacheMutex.Lock()
	defer cacheMutex.Unlock()
	cachedSuccessToken = token
	cachedTokenUsages = usages
}

func invalidateCachedToken() {
	cacheMutex.Lock()
	defer cacheMutex.Unlock()
	cachedSuccessToken = ""
	cachedTokenUsages = 0
}

var userAgent atomic.Value

func init() {
	userAgent.Store("Mozilla/5.0")
}

func SetUserAgent(ua string) {
	if ua != "" {
		userAgent.Store(ua)
	}
}

func getUserAgent() string {
	return userAgent.Load().(string)
}

func getSharedTransport() *http.Transport {
	sharedTransportOnce.Do(func() {
		dialer := &net.Dialer{
			Timeout: 10 * time.Second,
		}
		sharedTransport = &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			DialContext:           dialer.DialContext,
			ForceAttemptHTTP2:     true,
			MaxIdleConns:          100,
			MaxIdleConnsPerHost:   10,
			IdleConnTimeout:       90 * time.Second,
			TLSHandshakeTimeout:   10 * time.Second,
			ExpectContinueTimeout: 1 * time.Second,
		}
	})
	return sharedTransport
}

type Credentials struct {
	User     string
	Pass     string
	TurnURLs []string
	Lifetime int
}

func GetCredsWithFallback(ctx context.Context, tp *TurnParams, hash string, stats *Stats) (*Credentials, error) {
	creds, err := getUniqueVKCreds(ctx, hash, 5, stats)
	if err == nil {
		return creds, nil
	}
	if tp.SecondaryHash != "" && hash != tp.SecondaryHash {
		log.Println("Основной хеш не сработал, пробую запасной")
		return getUniqueVKCreds(ctx, tp.SecondaryHash, 3, stats)
	}
	return nil, err
}

type vkCaptchaError struct {
	ErrorCode      int
	ErrorMsg       string
	CaptchaSid     string
	RedirectUri    string
	SessionToken   string
	CaptchaTs      string
	CaptchaAttempt string
}

func parseVkCaptchaError(errData map[string]interface{}) *vkCaptchaError {
	codeFloat, _ := errData["error_code"].(float64)
	redirectUri, _ := errData["redirect_uri"].(string)
	errorMsg, _ := errData["error_msg"].(string)

	captchaSid, _ := errData["captcha_sid"].(string)
	if captchaSid == "" {
		if sidNum, ok := errData["captcha_sid"].(float64); ok {
			captchaSid = fmt.Sprintf("%.0f", sidNum)
		}
	}

	var sessionToken string
	if redirectUri != "" {
		if parsed, err := neturl.Parse(redirectUri); err == nil {
			sessionToken = parsed.Query().Get("session_token")
		}
	}

	var captchaTs string
	if tsFloat, ok := errData["captcha_ts"].(float64); ok {
		captchaTs = strconv.FormatFloat(tsFloat, 'f', -1, 64)
	} else if tsStr, ok := errData["captcha_ts"].(string); ok {
		captchaTs = tsStr
	}

	var captchaAttempt string
	if attFloat, ok := errData["captcha_attempt"].(float64); ok {
		captchaAttempt = fmt.Sprintf("%.0f", attFloat)
	} else if attStr, ok := errData["captcha_attempt"].(string); ok {
		captchaAttempt = attStr
	}

	return &vkCaptchaError{
		ErrorCode:      int(codeFloat),
		ErrorMsg:       errorMsg,
		CaptchaSid:     captchaSid,
		RedirectUri:    redirectUri,
		SessionToken:   sessionToken,
		CaptchaTs:      captchaTs,
		CaptchaAttempt: captchaAttempt,
	}
}

func solveVkCaptcha(ctx context.Context, captchaErr *vkCaptchaError, profile BotProfile) (string, error) {
	if captchaErr.SessionToken == "" {
		return "", fmt.Errorf("нет session_token в redirect_uri")
	}
	_ = profile

	mode := getCaptchaMode()
	if mode == "wv" {
		log.Printf("[КАПЧА] Режим: WebView (Manual)")
		return solveVkCaptchaViaWV(ctx, captchaErr, "manual")
	}

	log.Printf("[КАПЧА] Режим: WebView (Auto)")
	return solveVkCaptchaViaWV(ctx, captchaErr, "auto")
}

func getCaptchaMode() string {
	switch strings.ToLower(strings.TrimSpace(os.Getenv("NxiwNetwork_CAPTCHA_MODE"))) {
	case "wv":
		return "wv"
	default:
		return "auto"
	}
}

func SetCaptchaModeEnv(mode string) {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "wv":
		os.Setenv("NxiwNetwork_CAPTCHA_MODE", "wv")
	default:
		os.Setenv("NxiwNetwork_CAPTCHA_MODE", "auto")
	}
}

var captchaWVSem = make(chan struct{}, 1)

func solveVkCaptchaViaWV(ctx context.Context, captchaErr *vkCaptchaError, requestMode string) (string, error) {
	select {
	case captchaWVSem <- struct{}{}:
	case <-ctx.Done():
		return "", fmt.Errorf("отмена во время ожидания очереди капчи: %w", ctx.Err())
	}
	defer func() {
		// Даем Kotlin гарантированную секунду на очистку Mutex и WebView
		time.Sleep(1 * time.Second)
		<-captchaWVSem
	}()

	log.Printf("[КАПЧА] WV: Запрос отправлен (%s)", requestMode)

	drainCaptchaResult()

	fmt.Printf("CAPTCHA_SOLVE|%s|%s|%s\n", requestMode, captchaErr.RedirectUri, captchaErr.SessionToken)
	os.Stdout.Sync()

	select {
	case result := <-CaptchaResultCh:
		if strings.HasPrefix(result, "error:") {
			errMsg := strings.TrimPrefix(result, "error:")
			if !strings.Contains(errMsg, "tunnel stopped") {
				log.Printf("[КАПЧА] WV: Ошибка — %s", errMsg)
			}
			return "", fmt.Errorf("WV captcha error: %s", errMsg)
		}
		log.Printf("[КАПЧА] WV: Токен получен ✓")
		return result, nil
	case <-ctx.Done():
		return "", fmt.Errorf("отмена контекста во время ожидания капчи: %w", ctx.Err())
	case <-time.After(300 * time.Second):
		return "", fmt.Errorf("таймаут решения капчи через WV (5мин)")
	}
}

func selectTrafficTLSProfile(userAgent string) profiles.ClientProfile {
	switch GetActiveFingerprint() {
	case "safari":
		return profiles.Safari_16_0
	case "ios":
		return profiles.Safari_IOS_18_0
	case "firefox":
		return profiles.Firefox_132
	case "android":
		return profiles.Okhttp4Android13
	case "chrome", "edge", "linux", "macos":
		return profiles.Chrome_146
	}

	lowerUA := strings.ToLower(userAgent)
	switch {
	case strings.Contains(lowerUA, "android") || strings.Contains(lowerUA, "mobile"):
		return profiles.Okhttp4Android13
	case strings.Contains(lowerUA, "iphone") || strings.Contains(lowerUA, "ipad"):
		return profiles.Safari_IOS_18_0
	case strings.Contains(lowerUA, "firefox"):
		return profiles.Firefox_132
	case strings.Contains(lowerUA, "safari") && !strings.Contains(lowerUA, "chrome") && !strings.Contains(lowerUA, "chromium"):
		return profiles.Safari_16_0
	default:
		return profiles.Chrome_146
	}
}

func getUniqueVKCreds(ctx context.Context, hash string, maxRetries int, stats *Stats) (*Credentials, error) {
	var lastErr error

	realUA := getUserAgent()
	profileUA := forcedFingerprintUserAgent(realUA)
	actionSeed := uint64(time.Now().UnixNano()) ^ uint64(len(hash))
	profile := GenerateBotProfile(profileUA, hash, actionSeed)

	for attempt := 0; attempt < maxRetries; attempt++ {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case vkSemaphore <- struct{}{}:
		}

		creds, err := getVKCredsOnce(ctx, hash, profile)
		<-vkSemaphore

		if err == nil {
			return creds, nil
		}

		atomic.AddInt64(&stats.CredsErrors, 1)
		lastErr = err
		errStr := err.Error()

		if strings.Contains(errStr, "9000") || strings.Contains(errStr, "call not found") {
			return nil, fmt.Errorf("хеш мёртв: %w", err)
		}

		var backoff time.Duration
		if strings.Contains(errStr, "flood") || strings.Contains(errStr, "Flood") {
			secs := 5 * (attempt + 1)
			if secs > 60 {
				secs = 60
			}
			backoff = time.Duration(secs) * time.Second
		} else {
			base := 1 << uint(min(attempt, 5))
			if base > 30 {
				base = 30
			}
			backoff = time.Duration(base)*time.Second + time.Duration(rand.Intn(1000))*time.Millisecond
		}

		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(backoff):
		}
	}

	return nil, fmt.Errorf("исчерпаны %d попыток: %w", maxRetries, lastErr)
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func getVKCredsOnce(ctx context.Context, hash string, profile BotProfile) (*Credentials, error) {
	client, err := tlsclient.NewHttpClient(
		tlsclient.NewNoopLogger(),
		tlsclient.WithTimeoutSeconds(20),
		tlsclient.WithClientProfile(selectTrafficTLSProfile(profile.UserAgent)),
	)
	if err != nil {
		return nil, fmt.Errorf("tls-client init: %w", err)
	}

	okAppKey := "CGMMEJLGDIHBABABA"
	appID := vkAppID.Load().(string)
	appSecret := vkAppSecret.Load().(string)

	doReq := func(data, url string) (map[string]interface{}, error) {
		req, err := fhttp.NewRequestWithContext(ctx, "POST", url, bytes.NewBufferString(data))
		if err != nil {
			return nil, fmt.Errorf("создание запроса: %w", err)
		}
		req.Header.Set("User-Agent", profile.UserAgent)
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("sec-ch-ua-platform", firstNonEmpty(profile.SecChUaPlatform, `"Android"`))
		req.Header.Set("sec-ch-ua", firstNonEmpty(profile.SecChUa, `"Not(A:Brand";v="99", "Android WebView";v="133", "Chromium";v="133"`))
		req.Header.Set("sec-ch-ua-mobile", firstNonEmpty(profile.SecChUaMobile, "?1"))
		req.Header.Set("Sec-Fetch-Site", "cross-site")
		req.Header.Set("Sec-Fetch-Mode", "cors")
		req.Header.Set("Sec-Fetch-Dest", "empty")
		req.Header.Set("Accept", "*/*")
		req.Header.Set("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
		if strings.Contains(url, "api.vk.ru") {
			req.Header.Set("Origin", "https://vk.com")
			req.Header.Set("Referer", "https://vk.com/")
		} else {
			req.Header.Set("Origin", "https://login.vk.ru")
			req.Header.Set("Referer", "https://login.vk.ru/")
		}

		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()

		body, err := io.ReadAll(resp.Body)
		if err != nil {
			return nil, fmt.Errorf("чтение ответа: %w", err)
		}

		var m map[string]interface{}
		if err := json.Unmarshal(body, &m); err != nil {
			return nil, fmt.Errorf("парсинг JSON: %w | Body: %s", err, string(body))
		}
		return m, nil
	}

	checkAPIError := func(m map[string]interface{}, step string) error {
		if errObj, ok := m["error"]; ok {
			return fmt.Errorf("%s API error: %v", step, errObj)
		}
		return nil
	}

	get := func(m map[string]interface{}, keys ...string) (string, error) {
		var cur interface{} = m
		for _, k := range keys {
			mm, ok := cur.(map[string]interface{})
			if !ok {
				return "", fmt.Errorf("path %q not found", k)
			}
			cur = mm[k]
		}
		s, ok := cur.(string)
		if !ok {
			return "", fmt.Errorf("value at path is not string")
		}
		return s, nil
	}

	r, err := doReq(fmt.Sprintf(
		"client_secret=%s&client_id=%s&scopes=audio_anonymous%%2Cvideo_anonymous%%2Cphotos_anonymous%%2Cprofile_anonymous&isApiOauthAnonymEnabled=false&version=1&app_id=%s",
		appSecret, appID, appID,
	), "https://login.vk.ru/?act=get_anonym_token")
	if err != nil {
		return nil, fmt.Errorf("шаг 1: %w", err)
	}
	if err := checkAPIError(r, "шаг 1"); err != nil {
		return nil, err
	}
	t1, err := get(r, "data", "access_token")
	if err != nil {
		return nil, fmt.Errorf("шаг 1 парсинг: %w", err)
	}

	r, err = doReq(fmt.Sprintf(
		"client_id=%s&token_type=messages&payload=%s&client_secret=%s&version=1&app_id=%s",
		appID, t1, appSecret, appID,
	), "https://login.vk.ru/?act=get_anonym_token")
	if err != nil {
		return nil, fmt.Errorf("шаг 2: %w", err)
	}
	if err := checkAPIError(r, "шаг 2"); err != nil {
		return nil, err
	}
	t3, err := get(r, "data", "access_token")
	if err != nil {
		return nil, fmt.Errorf("шаг 2 парсинг: %w", err)
	}

	var t4 string

	postData := fmt.Sprintf(
		"vk_join_link=https://vk.com/call/join/%s&name=%s&access_token=%s",
		hash, neturl.QueryEscape(profile.Name), t3,
	)
	r, err = doReq(postData, "https://api.vk.ru/method/calls.getAnonymousToken?v=5.264")
	if err != nil {
		return nil, fmt.Errorf("шаг 3: %w", err)
	}

	if errObj, hasErr := r["error"].(map[string]interface{}); hasErr {
		errCode, _ := errObj["error_code"].(float64)
		if errCode == 14 {
			captchaErr := parseVkCaptchaError(errObj)
			log.Printf("[КАПЧА] Обнаружена: sid=%s, ts=%s, attempt=%s", captchaErr.CaptchaSid, captchaErr.CaptchaTs, captchaErr.CaptchaAttempt)
			if captchaErr.SessionToken == "" {
				return nil, fmt.Errorf("капча без session_token (старый тип)")
			}

			var successToken string
			var usedCache bool
			tokenFromCache := popCachedToken()
			if tokenFromCache != "" {
				log.Printf("[КАПЧА] Пробую использовать кэшированный success_token...")
				successToken = tokenFromCache
				usedCache = true
			} else {
				solveToken, solveErr := solveVkCaptcha(ctx, captchaErr, profile)
				if solveErr != nil {
					if !strings.Contains(solveErr.Error(), "tunnel stopped") {
						log.Printf("[КАПЧА] ОШИБКА решения: %v", solveErr)
					}
					return nil, fmt.Errorf("ошибка решения капчи: %w", solveErr)
				}
				successToken = solveToken
				log.Printf("[КАПЧА] Сохраняю success_token в кэш для 4 следующих группы")
				pushCachedToken(successToken, 4)
			}

			captchaAttemptStr := captchaErr.CaptchaAttempt
			if captchaAttemptStr == "0" || captchaAttemptStr == "" {
				captchaAttemptStr = "1"
			}

			postData = fmt.Sprintf(
				"vk_join_link=https://vk.com/call/join/%s&name=%s&access_token=%s&captcha_key=&captcha_sid=%s&is_sound_captcha=0&success_token=%s&captcha_ts=%s&captcha_attempt=%s",
				hash, neturl.QueryEscape(profile.Name), t3, captchaErr.CaptchaSid,
				neturl.QueryEscape(successToken), captchaErr.CaptchaTs, captchaAttemptStr,
			)
			r, err = doReq(postData, "https://api.vk.ru/method/calls.getAnonymousToken?v=5.264")
			if err != nil {
				return nil, fmt.Errorf("шаг 3 (после капчи): %w", err)
			}

			if errObj2, hasErr2 := r["error"].(map[string]interface{}); hasErr2 {
				errCode2, _ := errObj2["error_code"].(float64)
				if errCode2 == 14 {
					if usedCache {
						log.Printf("[КАПЧА] Кэшированный токен отклонён API, пробуем новую капчу...")
						invalidateCachedToken()
						return nil, fmt.Errorf("кэшированный токен отклонён")
					}
					time.Sleep(30 * time.Second)
					log.Printf("[КАПЧА] ОТКАЗ: VK всё ещё требует капчу после решения")
					return nil, fmt.Errorf("капча не пройдена после решения (пауза 30с)")
				}
				log.Printf("[КАПЧА] ОШИБКА после решения: %v", errObj2)
				return nil, fmt.Errorf("VK API error после капчи: %v", errObj2)
			}
			log.Printf("[КАПЧА] УСПЕХ: токен получен после решения капчи")
		} else {
			return nil, fmt.Errorf("VK API error: %v", errObj)
		}
	}

	t4, err = get(r, "response", "token")
	if err != nil {
		return nil, fmt.Errorf("шаг 3 парсинг: %w", err)
	}

	r, err = doReq(fmt.Sprintf(
		"session_data=%%7B%%22version%%22%%3A2%%2C%%22device_id%%22%%3A%%22%s%%22%%2C%%22client_version%%22%%3A1.1%%2C%%22client_type%%22%%3A%%22SDK_JS%%22%%7D&method=auth.anonymLogin&format=JSON&application_key=%s",
		uuid.New().String(), okAppKey,
	), "https://calls.okcdn.ru/fb.do")
	if err != nil {
		return nil, fmt.Errorf("шаг 4: %w", err)
	}
	if err := checkAPIError(r, "шаг 4"); err != nil {
		return nil, err
	}
	t5, err := get(r, "session_key")
	if err != nil {
		return nil, fmt.Errorf("шаг 4 парсинг: %w", err)
	}

	r, err = doReq(fmt.Sprintf(
		"joinLink=%s&isVideo=false&protocolVersion=5&anonymToken=%s&method=vchat.joinConversationByLink&format=JSON&application_key=%s&session_key=%s",
		hash, t4, okAppKey, t5,
	), "https://calls.okcdn.ru/fb.do")
	if err != nil {
		return nil, fmt.Errorf("шаг 5: %w", err)
	}
	if err := checkAPIError(r, "шаг 5"); err != nil {
		return nil, err
	}

	ts, ok := r["turn_server"].(map[string]interface{})
	if !ok {
		return nil, fmt.Errorf("turn_server не найден в ответе")
	}

	user, _ := ts["username"].(string)
	pass, _ := ts["credential"].(string)
	if user == "" || pass == "" {
		return nil, fmt.Errorf("пустые credentials в ответе")
	}

	lifetime, okLife := ts["lifetime"].(float64)
	if !okLife || lifetime <= 0 {
		ttl, okTtl := ts["ttl"].(float64)
		if okTtl && ttl > 0 {
			lifetime = ttl
		}
	}

	if lifetime > 0 {
		log.Printf("[ВК] Креды получены ✓")
	} else {
		log.Printf("[ВК] Креды получены ✓")
	}

	urls, _ := ts["urls"].([]interface{})
	var turnAddrs []string
	for _, u := range urls {
		s, ok := u.(string)
		if !ok {
			continue
		}
		clean := strings.Split(s, "?")[0]
		addr := strings.TrimPrefix(strings.TrimPrefix(clean, "turn:"), "turns:")
		if addr != "" {
			turnAddrs = append(turnAddrs, addr)
		}
	}
	if len(turnAddrs) == 0 {
		return nil, fmt.Errorf("нет TURN urls в ответе")
	}

	return &Credentials{User: user, Pass: pass, TurnURLs: turnAddrs, Lifetime: int(lifetime)}, nil
}
