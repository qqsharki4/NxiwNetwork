use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::fs;

#[derive(Clone, Debug, Deserialize)]
pub struct BrowserProfile {
    #[serde(default)]
    pub user_agent: String,
    #[serde(default)]
    pub sec_ch_ua: String,
    #[serde(default)]
    pub sec_ch_ua_mobile: String,
    #[serde(default)]
    pub sec_ch_ua_platform: String,
}

#[derive(Clone, Debug, Deserialize)]
struct SavedProfile {
    #[serde(flatten)]
    profile: BrowserProfile,
    #[serde(default)]
    device_json: String,
    #[serde(default)]
    browser_fp: String,
}

#[derive(Clone, Debug)]
pub struct BotProfile {
    pub user_agent: String,
    pub sec_ch_ua: String,
    pub sec_ch_ua_mobile: String,
    pub sec_ch_ua_platform: String,
    pub name: String,
    pub browser_fp: String,
    pub device_json: String,
    pub cursor_json: String,
    pub accelerometer: String,
    pub gyroscope: String,
    pub motion: String,
    pub taps: String,
    pub downlink: String,
    pub debug_info: String,
}

const PROFILE_FILE: &str = "vk_profile.json";

pub fn generate_bot_profile(
    real_user_agent: &str,
    fingerprint: &str,
    base_device_id: &str,
    action_seed: u64,
) -> BotProfile {
    let mut hw_hasher = Sha256::new();
    hw_hasher.update(base_device_id.as_bytes());
    hw_hasher.update(b"hardware_salt");
    let hw_digest = hw_hasher.finalize();
    let hw_seed = u64::from_be_bytes(hw_digest[..8].try_into().unwrap_or([0u8; 8]));

    let mut hw_rng = BotRng::new(hw_seed);
    let mut action_rng = BotRng::new(action_seed);

    let (browser_profile, saved_profile) = default_browser_profile(real_user_agent, fingerprint);

    let widths = [720i32, 1080, 1440];
    let width = widths[hw_rng.gen_range_usize(0, widths.len())];
    let ratio = 1.77 + hw_rng.gen_f64() * 0.56;
    let height = (f64::from(width) * ratio).round() as i32;
    let avail_height = height - (60 + hw_rng.gen_range_i32(0, 80));
    let inner_height = avail_height - hw_rng.gen_range_i32(0, 40);
    let dpr_choices = [2.0f64, 2.5, 2.75, 3.0, 3.5];
    let dpr = dpr_choices[hw_rng.gen_range_usize(0, dpr_choices.len())];
    let hw_threads = [4i32, 6, 8, 8, 8][hw_rng.gen_range_usize(0, 5)];
    let memory = [4i32, 6, 8, 12][hw_rng.gen_range_usize(0, 4)];
    let tz_offsets = [
        -180i32, -120, -240, -300, -360, -420, -480, -540, -600, -660,
    ];
    let tz_offset = tz_offsets[hw_rng.gen_range_usize(0, tz_offsets.len())];

    let mut device_json = format!(
        "{{\"screenWidth\":{width},\"screenHeight\":{height},\"screenAvailWidth\":{width},\"screenAvailHeight\":{avail_height},\"innerWidth\":{width},\"innerHeight\":{inner_height},\"devicePixelRatio\":{dpr},\"language\":\"ru-RU\",\"languages\":[\"ru-RU\",\"en-US\"],\"webdriver\":false,\"hardwareConcurrency\":{hw_threads},\"deviceMemory\":{memory},\"connectionEffectiveType\":\"4g\",\"notificationsPermission\":\"default\",\"timezoneOffset\":{tz_offset},\"platform\":\"Linux aarch64\",\"productSub\":\"20030107\",\"vendor\":\"Google Inc.\"}}"
    );
    let mut browser_fp = format!(
        "{:016x}{:016x}{:016x}{:016x}",
        hw_rng.next_u64(),
        hw_rng.next_u64(),
        hw_rng.next_u64(),
        hw_rng.next_u64()
    );
    if let Some(saved) = saved_profile {
        if !saved.device_json.trim().is_empty() {
            device_json = saved.device_json;
        }
        if !saved.browser_fp.trim().is_empty() {
            browser_fp = saved.browser_fp;
        }
    }

    let user_agent = first_non_empty(&[
        browser_profile.user_agent.clone(),
        real_user_agent.trim().to_string(),
        "Mozilla/5.0".to_string(),
    ]);
    let sec_ch_ua = first_non_empty(&[
        browser_profile.sec_ch_ua.clone(),
        "\"Chromium\";v=\"129\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"129\"".to_string(),
    ]);
    let sec_ch_ua_mobile =
        first_non_empty(&[browser_profile.sec_ch_ua_mobile.clone(), "?1".to_string()]);
    let sec_ch_ua_platform = first_non_empty(&[
        browser_profile.sec_ch_ua_platform.clone(),
        "\"Android\"".to_string(),
    ]);

    let (accelerometer, gyroscope, motion) = generate_motion_series(&mut hw_rng, &mut action_rng);

    BotProfile {
        user_agent,
        sec_ch_ua,
        sec_ch_ua_mobile,
        sec_ch_ua_platform,
        name: generate_name(&mut action_rng),
        browser_fp,
        device_json,
        cursor_json: generate_cursor_json(&mut action_rng),
        accelerometer,
        gyroscope,
        motion,
        taps: generate_mobile_taps(&mut action_rng, width, height),
        downlink: generate_downlink(&mut action_rng),
        debug_info: generate_debug_info(base_device_id),
    }
}

fn default_browser_profile(
    real_user_agent: &str,
    fingerprint: &str,
) -> (BrowserProfile, Option<SavedProfile>) {
    if let Some(saved) = load_profile_from_disk() {
        if !saved.profile.user_agent.trim().is_empty() {
            return (saved.profile.clone(), Some(saved));
        }
    }
    let mode = normalize_fingerprint(fingerprint);
    let selected_fingerprint = if mode == "auto" {
        infer_fingerprint_from_user_agent(real_user_agent)
    } else {
        mode.clone()
    };
    let mut selected = match selected_fingerprint.as_str() {
        "android" => android_profile(),
        "ios" => ios_profile(),
        "firefox" => firefox_profile(),
        "edge" => edge_profile(),
        "safari" => ios_profile(),
        "macos" => macos_profile(),
        "linux" => linux_profile(),
        _ => windows_chrome_profile(),
    };
    if mode == "auto" && !real_user_agent.trim().is_empty() {
        selected.user_agent = real_user_agent.trim().to_string();
    }
    (selected, None)
}

fn normalize_fingerprint(fingerprint: &str) -> String {
    match fingerprint.trim().to_ascii_lowercase().as_str() {
        "chrome" | "safari" | "firefox" | "edge" | "android" | "ios" | "linux" | "macos" => {
            fingerprint.trim().to_ascii_lowercase()
        }
        _ => "auto".to_string(),
    }
}

fn load_profile_from_disk() -> Option<SavedProfile> {
    let raw = fs::read_to_string(PROFILE_FILE).ok()?;
    serde_json::from_str(&raw).ok()
}

fn infer_fingerprint_from_user_agent(user_agent: &str) -> String {
    let lower = user_agent.to_ascii_lowercase();
    if lower.contains("android") {
        "android".to_string()
    } else if lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios") {
        "ios".to_string()
    } else if lower.contains("firefox") {
        "firefox".to_string()
    } else if lower.contains("edg/") {
        "edge".to_string()
    } else if lower.contains("mac os x") || lower.contains("macintosh") {
        "macos".to_string()
    } else if lower.contains("linux") || lower.contains("ubuntu") {
        "linux".to_string()
    } else {
        "chrome".to_string()
    }
}

fn windows_chrome_profile() -> BrowserProfile {
    BrowserProfile {
        user_agent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36".to_string(),
        sec_ch_ua: "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"".to_string(),
        sec_ch_ua_mobile: "?0".to_string(),
        sec_ch_ua_platform: "\"Windows\"".to_string(),
    }
}

fn edge_profile() -> BrowserProfile {
    BrowserProfile {
        user_agent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0".to_string(),
        sec_ch_ua: "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Microsoft Edge\";v=\"146\"".to_string(),
        sec_ch_ua_mobile: "?0".to_string(),
        sec_ch_ua_platform: "\"Windows\"".to_string(),
    }
}

fn macos_profile() -> BrowserProfile {
    BrowserProfile {
        user_agent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36".to_string(),
        sec_ch_ua: "\"Chromium\";v=\"145\", \"Not-A.Brand\";v=\"99\", \"Google Chrome\";v=\"145\"".to_string(),
        sec_ch_ua_mobile: "?0".to_string(),
        sec_ch_ua_platform: "\"macOS\"".to_string(),
    }
}

fn linux_profile() -> BrowserProfile {
    BrowserProfile {
        user_agent: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36".to_string(),
        sec_ch_ua: "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"".to_string(),
        sec_ch_ua_mobile: "?0".to_string(),
        sec_ch_ua_platform: "\"Linux\"".to_string(),
    }
}

fn firefox_profile() -> BrowserProfile {
    BrowserProfile {
        user_agent:
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0"
                .to_string(),
        sec_ch_ua: "\"Firefox\";v=\"132\", \"Not-A.Brand\";v=\"8\", \"Mozilla Firefox\";v=\"132\""
            .to_string(),
        sec_ch_ua_mobile: "?0".to_string(),
        sec_ch_ua_platform: "\"Windows\"".to_string(),
    }
}

fn android_profile() -> BrowserProfile {
    BrowserProfile {
        user_agent: "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36".to_string(),
        sec_ch_ua: "\"Chromium\";v=\"129\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"129\"".to_string(),
        sec_ch_ua_mobile: "?1".to_string(),
        sec_ch_ua_platform: "\"Android\"".to_string(),
    }
}

fn ios_profile() -> BrowserProfile {
    BrowserProfile {
        user_agent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1".to_string(),
        sec_ch_ua: "\"Safari\";v=\"17\", \"Not-A.Brand\";v=\"24\", \"Apple Safari\";v=\"17\"".to_string(),
        sec_ch_ua_mobile: "?1".to_string(),
        sec_ch_ua_platform: "\"iOS\"".to_string(),
    }
}

fn generate_debug_info(device_id: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(device_id.as_bytes());
    hasher.update(b"_debug_info_static_salt_v2");
    format!("{:x}", hasher.finalize())
}

fn generate_name(rng: &mut BotRng) -> String {
    let is_female = rng.gen_bool();
    let first_names = if is_female {
        FEMALE_FIRST_NAMES
    } else {
        MALE_FIRST_NAMES
    };
    let first_name = first_names[rng.gen_range_usize(0, first_names.len())];
    if rng.gen_f64() < 0.3 {
        return first_name.to_string();
    }
    let mut last_name =
        IDENTITY_LAST_NAMES[rng.gen_range_usize(0, IDENTITY_LAST_NAMES.len())].to_string();
    if is_female {
        last_name = convert_to_female_surname(&last_name);
    }
    format!("{first_name} {last_name}")
}

fn convert_to_female_surname(surname: &str) -> String {
    if surname.ends_with("ий") || surname.ends_with("ый") || surname.ends_with("ой") {
        let mut chars: Vec<char> = surname.chars().collect();
        chars.truncate(chars.len().saturating_sub(2));
        return chars.into_iter().collect::<String>() + "ая";
    }
    if surname.ends_with("ов")
        || surname.ends_with("ев")
        || surname.ends_with("ин")
        || surname.ends_with("ын")
        || surname.ends_with("ёв")
    {
        return format!("{surname}а");
    }
    surname.to_string()
}

fn generate_mobile_taps(rng: &mut BotRng, width: i32, height: i32) -> String {
    let scenario = rng.gen_range_usize(0, 10);
    let count = if scenario < 2 {
        0
    } else if scenario < 4 {
        1
    } else if scenario < 7 {
        2 + rng.gen_range_usize(0, 2)
    } else {
        4 + rng.gen_range_usize(0, 3)
    };
    if count == 0 {
        return "[]".to_string();
    }
    let mut base_time = 500 + rng.gen_range_i32(0, 1500);
    let mut taps = Vec::with_capacity(count);
    for index in 0..count {
        let tap_x = f64::from(width) * (0.15 + rng.gen_f64() * 0.7);
        let tap_y = f64::from(height) * (0.3 + rng.gen_f64() * 0.6);
        let duration = 50 + rng.gen_range_i32(0, 150);
        if index > 0 {
            base_time += 300 + rng.gen_range_i32(0, 1700);
        }
        taps.push(format!(
            "{{\"x\":{tap_x:.1},\"y\":{tap_y:.1},\"duration\":{duration},\"time\":{base_time}}}"
        ));
    }
    format!("[{}]", taps.join(","))
}

fn generate_motion_series(
    hw_rng: &mut BotRng,
    action_rng: &mut BotRng,
) -> (String, String, String) {
    let base_y = 4.0 + hw_rng.gen_f64() * 3.0;
    let base_z = 8.0 + hw_rng.gen_f64() * 1.5;
    let base_x = -1.0 + hw_rng.gen_f64() * 2.0;
    let count = 1 + action_rng.gen_range_usize(0, 5);

    let mut accel_events = Vec::with_capacity(count);
    let mut gyro_events = Vec::with_capacity(count);
    let mut motion_events = Vec::with_capacity(count);
    let (mut prev_ax, mut prev_ay, mut prev_az) = (base_x, base_y, base_z);
    let (mut prev_gx, mut prev_gy, mut prev_gz) = (0.0, 0.0, 0.0);

    for _ in 0..count {
        let tremor_x = action_rng.gen_f64() * 0.1 - 0.05;
        let tremor_y = action_rng.gen_f64() * 0.1 - 0.05;
        let tremor_z = action_rng.gen_f64() * 0.1 - 0.05;
        let drift = 0.3;

        let ax = prev_ax * drift + base_x * (1.0 - drift) + tremor_x;
        let ay = prev_ay * drift + base_y * (1.0 - drift) + tremor_y;
        let az = prev_az * drift + base_z * (1.0 - drift) + tremor_z;
        prev_ax = ax;
        prev_ay = ay;
        prev_az = az;
        accel_events.push(format!("{{\"x\":{ax:.3},\"y\":{ay:.3},\"z\":{az:.3}}}"));

        let gx = prev_gx * 0.7 + (action_rng.gen_f64() * 0.8 - 0.4) * 0.3;
        let gy = prev_gy * 0.7 + (action_rng.gen_f64() * 0.8 - 0.4) * 0.3;
        let gz = prev_gz * 0.7 + (action_rng.gen_f64() * 0.8 - 0.4) * 0.3;
        prev_gx = gx;
        prev_gy = gy;
        prev_gz = gz;
        gyro_events.push(format!(
            "{{\"alpha\":{gx:.2},\"beta\":{gy:.2},\"gamma\":{gz:.2}}}"
        ));
        motion_events.push(format!(
            "{{\"accelerationIncludingGravity\":{{\"x\":{ax:.3},\"y\":{ay:.3},\"z\":{az:.3}}}}}"
        ));
    }

    (
        format!("[{}]", accel_events.join(",")),
        format!("[{}]", gyro_events.join(",")),
        format!("[{}]", motion_events.join(",")),
    )
}

fn generate_downlink(rng: &mut BotRng) -> String {
    let count = 7 + rng.gen_range_usize(0, 10);
    let base = 10.0 + rng.gen_f64() * 20.0;
    let stabilize_after = 2 + rng.gen_range_usize(0, 3);
    let mut values = Vec::with_capacity(count);
    for index in 0..count {
        let value = if index < stabilize_after {
            base * (0.85 + rng.gen_f64() * 0.3)
        } else {
            base * (0.98 + rng.gen_f64() * 0.04)
        };
        values.push(format!("{value:.1}"));
    }
    format!("[{}]", values.join(","))
}

fn generate_cursor_json(rng: &mut BotRng) -> String {
    let start_x = 200.0 + rng.gen_f64() * 1520.0;
    let start_y = 200.0 + rng.gen_f64() * 680.0;
    let target_x = 960.0 + (rng.gen_f64() - 0.5) * 200.0;
    let target_y = 570.0 + (rng.gen_f64() - 0.5) * 100.0;
    let cp1x = start_x + (rng.gen_f64() - 0.5) * 500.0;
    let cp1y = start_y + (rng.gen_f64() - 0.5) * 300.0;
    let cp2x = target_x + (rng.gen_f64() - 0.5) * 150.0;
    let cp2y = target_y + (rng.gen_f64() - 0.5) * 80.0;
    let points_count = 6 + rng.gen_range_usize(0, 7);
    let mut points = Vec::with_capacity(points_count);

    for index in 0..points_count {
        let t = index as f64 / (points_count.saturating_sub(1)) as f64;
        let mt = 1.0 - t;
        let mut x = mt * mt * mt * start_x
            + 3.0 * mt * mt * t * cp1x
            + 3.0 * mt * t * t * cp2x
            + t * t * t * target_x;
        let mut y = mt * mt * mt * start_y
            + 3.0 * mt * mt * t * cp1y
            + 3.0 * mt * t * t * cp2y
            + t * t * t * target_y;
        x += rng.gen_f64() * 3.0 - 1.5;
        y += rng.gen_f64() * 3.0 - 1.5;
        points.push(format!("{{\"x\":{x:.1},\"y\":{y:.1}}}"));
    }
    format!("[{}]", points.join(","))
}

fn first_non_empty(values: &[String]) -> String {
    values
        .iter()
        .find(|value| !value.trim().is_empty())
        .cloned()
        .unwrap_or_default()
}

struct BotRng {
    state: u64,
}

impl BotRng {
    fn new(seed: u64) -> Self {
        Self {
            state: seed ^ 0x9e3779b97f4a7c15,
        }
    }

    fn next_u64(&mut self) -> u64 {
        self.state ^= self.state >> 12;
        self.state ^= self.state << 25;
        self.state ^= self.state >> 27;
        self.state = self.state.wrapping_mul(0x2545f4914f6cdd1d);
        self.state
    }

    fn gen_f64(&mut self) -> f64 {
        (self.next_u64() as f64) / (u64::MAX as f64)
    }

    fn gen_bool(&mut self) -> bool {
        self.next_u64() & 1 == 0
    }

    fn gen_range_usize(&mut self, start: usize, end: usize) -> usize {
        if end <= start {
            return start;
        }
        start + (self.next_u64() as usize % (end - start))
    }

    fn gen_range_i32(&mut self, start: i32, end: i32) -> i32 {
        if end <= start {
            return start;
        }
        start + (self.next_u64() % (end - start) as u64) as i32
    }
}

const MALE_FIRST_NAMES: &[&str] = &[
    "Александр",
    "Алексей",
    "Андрей",
    "Антон",
    "Арсений",
    "Артур",
    "Артём",
    "Богдан",
    "Валерий",
    "Василий",
    "Виктор",
    "Владислав",
    "Глеб",
    "Григорий",
    "Даниил",
    "Денис",
    "Дмитрий",
    "Евгений",
    "Егор",
    "Иван",
    "Игорь",
    "Илья",
    "Кирилл",
    "Леонид",
    "Максим",
    "Марк",
    "Матвей",
    "Михаил",
    "Никита",
    "Николай",
    "Олег",
    "Павел",
    "Пётр",
    "Роман",
    "Руслан",
    "Сергей",
    "Станислав",
    "Тимофей",
    "Фёдор",
];

const FEMALE_FIRST_NAMES: &[&str] = &[
    "Алина",
    "Алёна",
    "Анастасия",
    "Ангелина",
    "Анна",
    "Вера",
    "Вероника",
    "Виктория",
    "Дарья",
    "Ева",
    "Екатерина",
    "Елена",
    "Елизавета",
    "Ирина",
    "Кира",
    "Кристина",
    "Ксения",
    "Любовь",
    "Маргарита",
    "Марина",
    "Мария",
    "Милана",
    "Надежда",
    "Наталья",
    "Ольга",
    "Полина",
    "Светлана",
    "София",
    "Татьяна",
    "Юлия",
    "Яна",
];

const IDENTITY_LAST_NAMES: &[&str] = &[
    "Алексеев",
    "Андреев",
    "Антонов",
    "Баранов",
    "Белов",
    "Белый",
    "Бельский",
    "Беляев",
    "Борисов",
    "Васильев",
    "Великий",
    "Волков",
    "Воробьёв",
    "Григорьев",
    "Давыдов",
    "Егоров",
    "Жуков",
    "Зайцев",
    "Захаров",
    "Иванов",
    "Калинин",
    "Ковалёв",
    "Козлов",
    "Комаров",
    "Крамской",
    "Кузнецов",
    "Кузьмин",
    "Лебедев",
    "Макаров",
    "Медведев",
    "Михайлов",
    "Морозов",
    "Никитин",
    "Николаев",
    "Новиков",
    "Орлов",
    "Островский",
    "Павлов",
    "Петров",
    "Покровский",
    "Попов",
    "Раевский",
    "Романов",
    "Семёнов",
    "Сергеев",
    "Смирнов",
    "Соколов",
    "Соловьёв",
    "Степанов",
    "Тарасов",
    "Титов",
    "Толстой",
    "Трубецкой",
    "Филиппов",
    "Фролов",
    "Фёдоров",
    "Чайковский",
    "Черный",
    "Яковлев",
];
