package main

import (
	"encoding/json"
	"math/rand"
	"os"
	"strings"
)

type Profile struct {
	UserAgent       string `json:"user_agent"`
	SecChUa         string `json:"sec_ch_ua"`
	SecChUaMobile   string `json:"sec_ch_ua_mobile"`
	SecChUaPlatform string `json:"sec_ch_ua_platform"`
}

type SavedProfile struct {
	Profile
	DeviceJSON string `json:"device_json"`
	BrowserFp  string `json:"browser_fp"`
}

const profileFile = "vk_profile.json"

func LoadProfileFromDisk() (*SavedProfile, error) {
	data, err := os.ReadFile(profileFile)
	if err != nil {
		return nil, err
	}
	var sp SavedProfile
	if err := json.Unmarshal(data, &sp); err != nil {
		return nil, err
	}
	return &sp, nil
}

var profileList = []Profile{
	{UserAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36", SecChUa: `"Chromium";v="146", "Not-A.Brand";v="24", "Google Chrome";v="146"`, SecChUaMobile: "?0", SecChUaPlatform: `"Windows"`},
	{UserAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36", SecChUa: `"Chromium";v="145", "Not-A.Brand";v="99", "Google Chrome";v="145"`, SecChUaMobile: "?0", SecChUaPlatform: `"Windows"`},
	{UserAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36", SecChUa: `"Chromium";v="144", "Not-A.Brand";v="8", "Google Chrome";v="144"`, SecChUaMobile: "?0", SecChUaPlatform: `"Windows"`},
	{UserAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0", SecChUa: `"Chromium";v="146", "Not-A.Brand";v="24", "Microsoft Edge";v="146"`, SecChUaMobile: "?0", SecChUaPlatform: `"Windows"`},
	{UserAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0", SecChUa: `"Chromium";v="145", "Not-A.Brand";v="99", "Microsoft Edge";v="145"`, SecChUaMobile: "?0", SecChUaPlatform: `"Windows"`},
	{UserAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36", SecChUa: `"Chromium";v="146", "Not-A.Brand";v="24", "Google Chrome";v="146"`, SecChUaMobile: "?0", SecChUaPlatform: `"macOS"`},
	{UserAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36", SecChUa: `"Chromium";v="145", "Not-A.Brand";v="99", "Google Chrome";v="145"`, SecChUaMobile: "?0", SecChUaPlatform: `"macOS"`},
	{UserAgent: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36", SecChUa: `"Chromium";v="146", "Not-A.Brand";v="24", "Google Chrome";v="146"`, SecChUaMobile: "?0", SecChUaPlatform: `"Linux"`},
	{UserAgent: "Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36", SecChUa: `"Chromium";v="144", "Not-A.Brand";v="8", "Google Chrome";v="144"`, SecChUaMobile: "?0", SecChUaPlatform: `"Linux"`},
	{UserAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0", SecChUa: `"Firefox";v="132", "Not-A.Brand";v="8", "Mozilla Firefox";v="132"`, SecChUaMobile: "?0", SecChUaPlatform: `"Windows"`},
}

var androidProfiles = []Profile{
	{UserAgent: "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36", SecChUa: `"Chromium";v="129", "Not-A.Brand";v="24", "Google Chrome";v="129"`, SecChUaMobile: "?1", SecChUaPlatform: `"Android"`},
}

var iosProfiles = []Profile{
	{UserAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1", SecChUa: `"Safari";v="17", "Not-A.Brand";v="24", "Apple Safari";v="17"`, SecChUaMobile: "?1", SecChUaPlatform: `"iOS"`},
}

var activeFingerprint = "chrome"

func SetActiveFingerprint(fp string) { activeFingerprint = fp }
func GetActiveFingerprint() string   { return activeFingerprint }

func getRandomProfile() Profile {
	switch activeFingerprint {
	case "android":
		return androidProfiles[rand.Intn(len(androidProfiles))]
	case "ios":
		return iosProfiles[rand.Intn(len(iosProfiles))]
	case "safari":
		return profileList[5]
	case "firefox":
		return profileList[len(profileList)-1]
	case "edge":
		return profileList[3+rand.Intn(2)]
	case "linux":
		return profileList[7+rand.Intn(2)]
	case "macos":
		return profileList[5+rand.Intn(2)]
	default:
		return profileList[rand.Intn(3)]
	}
}

func inferFingerprintFromUserAgent(userAgent string) string {
	lowerUA := strings.ToLower(strings.TrimSpace(userAgent))
	switch {
	case strings.Contains(lowerUA, "android"):
		return "android"
	case strings.Contains(lowerUA, "iphone") || strings.Contains(lowerUA, "ipad") || strings.Contains(lowerUA, "ios"):
		return "ios"
	case strings.Contains(lowerUA, "firefox"):
		return "firefox"
	case strings.Contains(lowerUA, "edg/"):
		return "edge"
	case strings.Contains(lowerUA, "safari") && !strings.Contains(lowerUA, "chrome") && !strings.Contains(lowerUA, "chromium"):
		return "safari"
	case strings.Contains(lowerUA, "mac os x") || strings.Contains(lowerUA, "macintosh"):
		return "macos"
	case strings.Contains(lowerUA, "linux") || strings.Contains(lowerUA, "ubuntu"):
		return "linux"
	default:
		return "chrome"
	}
}

func defaultBotBrowserProfile(realUserAgent string) (Profile, *SavedProfile) {
	if saved, err := LoadProfileFromDisk(); err == nil && strings.TrimSpace(saved.UserAgent) != "" {
		return saved.Profile, saved
	}
	SetActiveFingerprint(inferFingerprintFromUserAgent(realUserAgent))
	selected := getRandomProfile()
	if ua := strings.TrimSpace(realUserAgent); ua != "" {
		selected.UserAgent = ua
	}
	return selected, nil
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}
