package com.nxiwnetwork.client

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader

private const val PING_HOME_LOOP_DELAY_MS = 400L
private const val PING_BACKGROUND_LOOP_DELAY_MS = 10_000L
private const val PING_SCREEN_OFF_LOOP_DELAY_MS = 30_000L

@Stable
data class LogEntry(
    val key: String,
    val message: String,
    val count: Int = 1,
    val priority: Int = 99, 
    val isError: Boolean = false
)

@Stable
data class CoreTrafficMetrics(
    val activeConnections: Int = 0,
    val totalUpBytes: Long = 0L,
    val totalDownBytes: Long = 0L,
    val upBytesPerSecond: Long = 0L,
    val downBytesPerSecond: Long = 0L,
    val packetsUp: Long = 0L,
    val packetsDown: Long = 0L,
    val upPacketsPerSecond: Long = 0L,
    val downPacketsPerSecond: Long = 0L,
    val droppedPackets: Long = 0L,
    val droppedNoWorkers: Long = 0L,
    val droppedWorkerQueue: Long = 0L,
    val droppedNoClient: Long = 0L,
    val droppedLocalWrite: Long = 0L
) {
    val totalBytes: Long get() = totalUpBytes + totalDownBytes
    val speedBytesPerSecond: Long get() = upBytesPerSecond + downBytesPerSecond
}

@Stable
data class AndroidPingSchedule(
    val homeIntervalMs: Int = SettingsStore.DEFAULT_ANDROID_PING_HOME_INTERVAL_MS,
    val backgroundIntervalMs: Int = SettingsStore.DEFAULT_ANDROID_PING_BACKGROUND_INTERVAL_MS
)

@Stable
data class ActiveTunnelNode(
    val serverId: String = "",
    val peer: String = "",
    val connectionPassword: String = ""
)

object TunnelManager {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null
    private var readerJob: Job? = null
    private var watchdogJob: Job? = null
    private var metricsJob: Job? = null
    private var runtimeSettingsJob: Job? = null
    private var runtimeWorkerApplyJob: Job? = null
    private var wgHelper: WireGuardHelper? = null
    @Volatile private var processRestartExpected = false
    @Volatile private var processStopExpected = false
    @Volatile private var userRequestedStopPending = false

    private var floodCount = 0
    private var mismatchCount = 0
    private var refusedCount = 0
    private var currentHashErrorCount = 0
    private var activeHashIndex = 0 
    private var currentParams: TunnelParams? = null
    private var lastContext: Context? = null
    private var forceRegenerateUA = false 
    private var currentCaptchaMode = "auto"

    val running = MutableStateFlow(false)
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val unreadErrorCount = MutableStateFlow(0)
    val config = MutableStateFlow<String?>(null)
    val stats = MutableStateFlow("Ожидание данных...")
    val activeWorkers = MutableStateFlow(0)
    val activeCoreBackend = MutableStateFlow<CoreBackend?>(null)
    val coreProtocolMode = MutableStateFlow("нет данных")
    val coreProcessPid = MutableStateFlow<Int?>(null)
    val tunnelStartedAtElapsedMs = MutableStateFlow<Long?>(null)
    val activeRequestedMtu = MutableStateFlow(0)
    val activeRequestedDns = MutableStateFlow("")
    val activeWireGuardMtu = MutableStateFlow(0)
    val activeWireGuardDns = MutableStateFlow("")
    val activeTunnelNode = MutableStateFlow(ActiveTunnelNode())
    
    val cooldownSeconds = MutableStateFlow(0)
    private var cooldownJob: Job? = null

    val currentPingMs = MutableStateFlow(0)
    val currentSpeedBytes = MutableStateFlow(0L)
    val currentUploadSpeedBytes = MutableStateFlow(0L)
    val currentDownloadSpeedBytes = MutableStateFlow(0L)
    val coreTrafficMetrics = MutableStateFlow(CoreTrafficMetrics())
    val trafficGraphPoints = MutableStateFlow(List(30) { 0f })
    val uploadTrafficGraphPoints = MutableStateFlow(List(30) { 0f })
    val downloadTrafficGraphPoints = MutableStateFlow(List(30) { 0f })
    val coreTrafficMetricsUiEnabled = MutableStateFlow(true)
    val pingMetricsEnabled = MutableStateFlow(true)
    val androidPingSchedule = MutableStateFlow(AndroidPingSchedule())
    private val appForeground = MutableStateFlow(false)
    private val dashboardVisible = MutableStateFlow(false)
    private val pingScheduleSignal = MutableStateFlow(0L)

    fun setAppForeground(foreground: Boolean) {
        if (appForeground.value != foreground) {
            appForeground.value = foreground
            pingScheduleSignal.value = SystemClock.elapsedRealtime()
        }
    }

    fun setDashboardVisible(visible: Boolean) {
        if (dashboardVisible.value != visible) {
            dashboardVisible.value = visible
            pingScheduleSignal.value = SystemClock.elapsedRealtime()
        }
    }

    fun setAndroidPingSchedule(homeIntervalMs: Int, backgroundIntervalMs: Int) {
        val next = AndroidPingSchedule(
            homeIntervalMs = homeIntervalMs.coerceIn(
                SettingsStore.MIN_ANDROID_PING_HOME_INTERVAL_MS,
                SettingsStore.MAX_ANDROID_PING_HOME_INTERVAL_MS
            ),
            backgroundIntervalMs = backgroundIntervalMs.coerceIn(
                SettingsStore.MIN_ANDROID_PING_BACKGROUND_INTERVAL_MS,
                SettingsStore.MAX_ANDROID_PING_BACKGROUND_INTERVAL_MS
            )
        )
        if (androidPingSchedule.value != next) {
            androidPingSchedule.value = next
            pingScheduleSignal.value = SystemClock.elapsedRealtime()
        }
    }

    private fun updateWidgetState() {
        val ctx = lastContext ?: return
        try {
            val intent = Intent(ctx, NxiwWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val ids = AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(ComponentName(ctx, NxiwWidgetProvider::class.java))
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            ctx.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    fun clearUnreadErrors() {
        unreadErrorCount.value = 0
    }

    fun addDeployErrorLog(message: String) {
        val hash = message.hashCode().toString()
        updateLog("deploy_err_$hash", "[ДЕПЛОЙ] $message", 99, true)
    }

    fun addDeploySuccessLog(message: String) {
        val hash = message.hashCode().toString() + System.currentTimeMillis()
        updateLog("deploy_ok_$hash", message, 2, false)
    }

    private fun updateLog(key: String, message: String, priority: Int, isError: Boolean = false) {
        if (isError) {
            val list = logs.value
            if (list.none { it.key == key }) {
                unreadErrorCount.value++
            }
        }
        logs.update { currentList ->
            val current = currentList.toMutableList()
            val index = current.indexOfFirst { it.key == key }

            if (index != -1) {
                val entry = current[index]
                current[index] = entry.copy(count = entry.count + 1, message = message, priority = priority, isError = isError)
            } else {
                current.add(LogEntry(key, message, 1, priority, isError))
            }

            val sorted = current.sortedWith(compareBy({ it.priority }, { if (it.isError) 1 else 0 }, { it.key }))
            if (sorted.size > 100) sorted.takeLast(100) else sorted
        }
    }

    private fun startMetricsMonitor(ip: String) {
        metricsJob?.cancel()
        metricsJob = scope.launch(Dispatchers.IO) {
            var lastPingAt = 0L
            var lastSignal = pingScheduleSignal.value
            
            while (isActive && running.value) {
                val signal = pingScheduleSignal.value
                if (signal != lastSignal) {
                    lastSignal = signal
                    lastPingAt = 0L
                }

                if (!pingMetricsEnabled.value) {
                    currentPingMs.value = 0
                    awaitPingScheduleDelay(PING_BACKGROUND_LOOP_DELAY_MS, lastSignal)
                    continue
                }

                if (!isScreenInteractive()) {
                    awaitPingScheduleDelay(PING_SCREEN_OFF_LOOP_DELAY_MS, lastSignal)
                    continue
                }

                val schedule = androidPingSchedule.value
                val homeVisible = (appForeground.value || MainActivity.isForeground) && dashboardVisible.value
                val intervalMs = if (homeVisible) schedule.homeIntervalMs.toLong() else schedule.backgroundIntervalMs.toLong()
                val now = SystemClock.elapsedRealtime()
                if (lastPingAt == 0L || now - lastPingAt >= intervalMs) {
                    lastPingAt = now
                    currentPingMs.value = measurePing(ip)
                }

                val delayMs = if (homeVisible) {
                    PING_HOME_LOOP_DELAY_MS
                } else {
                    PING_BACKGROUND_LOOP_DELAY_MS
                }
                awaitPingScheduleDelay(delayMs, lastSignal)
            }
        }
    }

    private suspend fun awaitPingScheduleDelay(delayMs: Long, signal: Long) {
        withTimeoutOrNull(delayMs) {
            pingScheduleSignal.first { it != signal }
        }
    }

    private fun isScreenInteractive(): Boolean {
        val context = lastContext ?: return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive ?: true
    }

    private fun measurePing(ip: String): Int {
        var process: Process? = null
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "1", ip))
            process = proc
            var ping = 0
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                reader.forEachLine { line ->
                    if (line.contains("time=")) {
                        val timeStr = line.substringAfter("time=").substringBefore(" ms")
                        ping = timeStr.toFloatOrNull()?.toInt() ?: 0
                    }
                }
            }
            proc.waitFor()
            if (ping > 0) ping else 0
        } catch (_: Exception) {
            0
        } finally {
            process?.destroy()
        }
    }

    private fun applyCoreMetrics(metrics: CoreTrafficMetrics) {
        coreTrafficMetrics.value = metrics
        activeWorkers.value = metrics.activeConnections
        currentUploadSpeedBytes.value = metrics.upBytesPerSecond
        currentDownloadSpeedBytes.value = metrics.downBytesPerSecond
        currentSpeedBytes.value = metrics.speedBytesPerSecond
        appendTrafficGraphPoint(trafficGraphPoints, metrics.speedBytesPerSecond)
        appendTrafficGraphPoint(uploadTrafficGraphPoints, metrics.upBytesPerSecond)
        appendTrafficGraphPoint(downloadTrafficGraphPoints, metrics.downBytesPerSecond)
    }

    fun setCoreTrafficMetricsUiEnabled(enabled: Boolean) {
        coreTrafficMetricsUiEnabled.value = enabled
        if (!enabled) {
            currentUploadSpeedBytes.value = 0L
            currentDownloadSpeedBytes.value = 0L
            currentSpeedBytes.value = 0L
            trafficGraphPoints.value = List(30) { 0f }
            uploadTrafficGraphPoints.value = List(30) { 0f }
            downloadTrafficGraphPoints.value = List(30) { 0f }
        }
    }

    fun setPingMetricsEnabled(enabled: Boolean) {
        if (pingMetricsEnabled.value != enabled) {
            pingMetricsEnabled.value = enabled
            pingScheduleSignal.value = SystemClock.elapsedRealtime()
        }
        if (!enabled) currentPingMs.value = 0
    }

    private fun resetCoreMetrics() {
        coreTrafficMetrics.value = CoreTrafficMetrics()
        activeWorkers.value = 0
        currentUploadSpeedBytes.value = 0L
        currentDownloadSpeedBytes.value = 0L
        currentSpeedBytes.value = 0L
        trafficGraphPoints.value = List(30) { 0f }
        uploadTrafficGraphPoints.value = List(30) { 0f }
        downloadTrafficGraphPoints.value = List(30) { 0f }
    }

    private fun appendTrafficGraphPoint(target: MutableStateFlow<List<Float>>, bytesPerSecond: Long) {
        target.update { points ->
            (points.drop(1) + bytesPerSecond.coerceAtLeast(0L).toFloat()).takeLast(30)
        }
    }

    private fun parseCoreMetrics(message: String): CoreTrafficMetrics? {
        val values = Regex("""([a-z_]+)=(-?\d+)""")
            .findAll(message)
            .mapNotNull { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                key to value
            }
            .toMap()

        if (values.isEmpty()) return null

        return CoreTrafficMetrics(
            activeConnections = values["active"]?.toInt() ?: 0,
            totalUpBytes = values["total_up"] ?: 0L,
            totalDownBytes = values["total_down"] ?: 0L,
            upBytesPerSecond = values["up_bps"] ?: 0L,
            downBytesPerSecond = values["down_bps"] ?: 0L,
            packetsUp = values["packets_up"] ?: 0L,
            packetsDown = values["packets_down"] ?: 0L,
            upPacketsPerSecond = values["up_pps"] ?: 0L,
            downPacketsPerSecond = values["down_pps"] ?: 0L,
            droppedPackets = values["drops"] ?: 0L,
            droppedNoWorkers = values["drop_no_workers"] ?: 0L,
            droppedWorkerQueue = values["drop_worker_queue"] ?: 0L,
            droppedNoClient = values["drop_no_client"] ?: 0L,
            droppedLocalWrite = values["drop_local_write"] ?: 0L
        )
    }

    private fun formatCoreProtocolMode(message: String): String {
        val values = Regex("""([a-z_]+)=([^ ]+)""")
            .findAll(message)
            .associate { it.groupValues[1] to it.groupValues[2] }

        val request = when (values["request"] ?: values["mode"]) {
            "legacy" -> "Legacy GETCONF"
            "extended_legacy" -> "Extended GETCONF"
            "json" -> "JSON"
            else -> values["request"] ?: values["mode"] ?: "unknown"
        }
        val response = when (values["response"]) {
            "raw_config" -> "raw config"
            "config" -> "JSON config"
            "no_config" -> "no config"
            "denied", "error" -> "denied"
            else -> values["response"] ?: "unknown"
        }
        val protocol = values["protocol"] ?: values["proto"]?.let { "v$it" } ?: "legacy?"
        val json = values["json"] == "true"
        val dns = when (values["dns"]) {
            "not_requested" -> "not requested"
            "matches_config" -> "matches config"
            "not_in_config" -> "ignored"
            "missing" -> "missing"
            "true" -> "requested"
            "false" -> "not requested"
            else -> values["dns"] ?: "unknown"
        }
        val caps = values["caps"]?.takeIf { it != "none" } ?: "none"
        val policy = values["policy"]?.let { " / policy=$it" } ?: ""
        val applied = buildList {
            values["applied_dns"]?.takeIf { it.isNotBlank() }?.let { add("DNS $it") }
            values["applied_mtu"]?.takeIf { it.isNotBlank() }?.let { add("MTU $it") }
        }.joinToString(", ")
        val appliedText = if (applied.isNotEmpty()) " / applied=$applied" else ""
        return "$request -> $response / $protocol / json=${if (json) "yes" else "no"} / dns=$dns / caps=$caps$policy$appliedText"
    }

    fun start(context: Context, params: TunnelParams, isSwitching: Boolean = false) {
        if (running.value && !isSwitching) return
        
        val appContext = context.applicationContext 
        lastContext = appContext
        
        if (!isSwitching) {
            clearLogs()
            floodCount = 0
            mismatchCount = 0
            refusedCount = 0
            currentHashErrorCount = 0
            activeHashIndex = 0
            currentParams = params
            forceRegenerateUA = false
            currentCaptchaMode = params.captchaMode
            resetCoreMetrics()
            currentPingMs.value = 0
            activeTunnelNode.value = ActiveTunnelNode()
            tunnelStartedAtElapsedMs.value = SystemClock.elapsedRealtime()
        }
        
        wgHelper = WireGuardHelper(appContext)

        scope.launch {
            try {
                val targetHash = if (activeHashIndex == 0) params.vkHashes else params.secondaryVkHash
                
                val hashList = normalizeVkHashList(targetHash)
                    .split(",")
                    .filter { it.isNotEmpty() }
                    .take(3)

                if (hashList.isEmpty()) {
                    updateLog("hash_error", "Ошибка: Хеш не указан", 99, true)
                    running.value = false
                    updateWidgetState()
                    return@launch
                }

                val hashCount = hashList.size.coerceIn(1, 3)
                val totalWorkers = params.workersPerHash.coerceIn(1, 128)
                val keepaliveSeconds = params.clientKeepaliveSeconds.coerceIn(5, 60)
                
                val hashMode = if (activeHashIndex == 0) "Основной" else "Запасной"
                updateLog("config_info", "[$hashMode] Хешей=$hashCount, Потоков=$totalWorkers, Keepalive=${keepaliveSeconds}с", 1)

                val settingsStore = SettingsStore(appContext)
                val requestedBackend = CoreBackend.fromId(settingsStore.coreBackend.first())
                setCoreTrafficMetricsUiEnabled(settingsStore.coreTrafficMetricsUi.first())
                setPingMetricsEnabled(settingsStore.pingMetricsUi.first())
                setAndroidPingSchedule(
                    settingsStore.androidPingHomeIntervalMs.first(),
                    settingsStore.androidPingBackgroundIntervalMs.first()
                )
                val backendResolution = resolveCoreBackend(context.applicationInfo.nativeLibraryDir, requestedBackend)
                val binaryFile = backendResolution.binaryFile
                
                if (!binaryFile.exists()) {
                    updateLog("binary_error", "Ошибка: Бинарный файл не найден", 99, true)
                    running.value = false
                    updateWidgetState()
                    return@launch
                }
                if (backendResolution.fellBackToGo) {
                    updateLog(
                        "core_backend_fallback",
                        "[ЯДРО] ${backendResolution.requested.label} не найден в APK, запускаю Go",
                        2
                    )
                } else {
                    updateLog("core_backend", "[ЯДРО] Backend: ${backendResolution.active.label}", 2)
                }

                val peerEndpoint = normalizeNodeEndpoint(params.peer)
                val binaryPath = binaryFile.absolutePath
                val cmd = mutableListOf(
                    binaryPath,
                    "-peer", peerEndpoint,
                    "-vk", hashList.joinToString(","),
                    "-n", totalWorkers.toString(),
                    "-keepalive-sec", keepaliveSeconds.toString(),
                    "-listen", "127.0.0.1:${params.port}"
                )

                if (params.sni.isNotEmpty()) {
                    cmd.add("-sni")
                    cmd.add(params.sni)
                }
                val dnsOverride = resolveCoreDnsOverride(settingsStore)
                activeRequestedDns.value = dnsOverride
                if (dnsOverride.isNotEmpty()) {
                    cmd.add("-dns")
                    cmd.add(dnsOverride)
                }
                val mtuOverride = settingsStore.customMtu.first()
                activeRequestedMtu.value = mtuOverride
                if (mtuOverride > 0) {
                    cmd.add("-mtu")
                    cmd.add(mtuOverride.toString())
                }

                val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
                cmd.add("-device-id")
                cmd.add(androidId)

                if (params.connectionPassword.isNotEmpty()) {
                    cmd.add("-password")
                    cmd.add(params.connectionPassword)
                }

                val wrapSupported = supportsWrapTransport(requestedBackend, params.protocol) &&
                    supportsWrapTransport(backendResolution.active, params.protocol)
                if (params.wrapTransport && wrapSupported) {
                    if (params.connectionPassword.isBlank()) {
                        updateLog("wrap_no_password", "[WRAP] Нужен пароль подключения", 20, true)
                    } else {
                        cmd.add("-wrap")
                        updateLog("wrap_transport", "[WRAP] OBFS включен", 20)
                    }
                }

                cmd.add(if (params.protocol == "tcp") "-tcp" else "-udp")
                cmd.add("-captcha-mode")
                cmd.add(params.captchaMode)
                cmd.add("-fingerprint")
                cmd.add(settingsStore.trafficFingerprint.first())

                var userAgent = settingsStore.userAgent.first()
                if (userAgent.isEmpty() || forceRegenerateUA) {
                    userAgent = UserAgentGenerator.generateForDevice(androidId)
                    settingsStore.saveUserAgent(userAgent)
                    forceRegenerateUA = false
                    updateLog("ua_generated", "[UA] Сгенерирован новый User-Agent", 50)
                }
                cmd.add("-user-agent")
                cmd.add(userAgent)

                val pb = ProcessBuilder(cmd)
                pb.directory(context.filesDir) 
                pb.redirectErrorStream(true)
                
                val env = pb.environment()
                env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

                process = pb.start()
                coreProcessPid.value = process?.safePid()
                running.value = true
                activeTunnelNode.value = ActiveTunnelNode(
                    serverId = settingsStore.selectedServerId.first(),
                    peer = peerEndpoint,
                    connectionPassword = params.connectionPassword.trim()
                )
                activeCoreBackend.value = backendResolution.active
                coreProtocolMode.value = "ожидание handshake"
                stats.value = "Ожидание данных..."
                startRuntimeSettingsMonitor(settingsStore, totalWorkers)
                updateWidgetState()
                
                val serverIp = nodeEndpointHost(peerEndpoint).ifBlank { params.peer.substringBefore(":") }
                startMetricsMonitor(serverIp)
                startLogReader()
                startWatchdog(appContext, params)

            } catch (e: Exception) {
                updateLog("critical_start_error", "Критическая ошибка запуска: ${e.message}", 99, true)
                running.value = false
                activeCoreBackend.value = null
                coreProtocolMode.value = "нет данных"
                activeTunnelNode.value = ActiveTunnelNode()
                updateWidgetState()
            }
        }
    }

    private suspend fun resolveCoreDnsOverride(settingsStore: SettingsStore): String {
        return when (settingsStore.customDns.first()) {
            "adguard" -> "94.140.14.14,94.140.15.15"
            "cloudflare" -> "1.1.1.1,1.0.0.1"
            "custom" -> settingsStore.customDnsIp.first().trim()
            else -> ""
        }
    }

    private fun startLogReader() {
        readerJob = scope.launch {
            val readerProcess = process ?: return@launch
            val reader = readerProcess.inputStream.bufferedReader()
            var collectingConfig = false
            val configBuilder = StringBuilder()

            try {
                var lastResetTime = System.currentTimeMillis()

                reader.forEachLine { line ->
                    val now = System.currentTimeMillis()
                    if (now - lastResetTime > 60000) {
                        refusedCount = 0
                        floodCount = 0
                        mismatchCount = 0
                        currentHashErrorCount = 0
                        lastResetTime = now
                    }

                    val msgPrefixReplaced = line.replace(Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s"), "")
                    val lineTrim = msgPrefixReplaced.trim()

                    val isError = lineTrim.contains("Ошибка", true) || lineTrim.contains("error", true) || lineTrim.contains("FAIL", true) || lineTrim.contains("timeout", true) || lineTrim.contains("refused", true) || lineTrim.contains("FATAL_AUTH", true)

                    if (lineTrim.contains("FATAL_AUTH")) {
                        val reason = when {
                            lineTrim.contains("неверный пароль") -> "Неверный пароль подключения"
                            lineTrim.contains("истёк") -> "Срок действия пароля истёк"
                            lineTrim.contains("другому устройству") -> "Пароль привязан к другому устройству"
                            else -> "Ошибка авторизации"
                        }
                        handleCriticalError("\uD83D\uDD12 $reason. Воркеры остановлены.")
                        return@forEachLine
                    }

                    if (lineTrim.startsWith("CAPTCHA_SOLVE|")) {
                        val payload = lineTrim.substringAfter("CAPTCHA_SOLVE|")
                        val parts = payload.split("|")
                        when (parts.size) {
                            2 -> {
                                val redirectUri = parts[0]
                                val sessionToken = parts[1]
                                scope.launch {
                                    handleCaptchaSolve("selected", redirectUri, sessionToken)
                                }
                            }
                            3 -> {
                                val requestMode = parts[0]
                                val redirectUri = parts[1]
                                val sessionToken = parts[2]
                                scope.launch {
                                    handleCaptchaSolve(requestMode, redirectUri, sessionToken)
                                }
                            }
                            else -> writeCaptchaResult("error:invalid CAPTCHA_SOLVE format")
                        }
                        return@forEachLine
                    }

                    if (isError) {
                        when {
                            lineTrim.contains("Flood control", true) -> {
                                floodCount++
                                if (floodCount >= 5) {
                                    handleCriticalError("Flood Control (ВК ограничил ваш IP). Попробуйте позже.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("ip mismatch", true) -> {
                                mismatchCount++
                                if (mismatchCount >= 5) {
                                    handleCriticalError("IP Mismatch (IP утерян). Попробуйте переподключиться.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("connection refused", true) || lineTrim.contains("timeout", true) -> {
                                refusedCount++
                                if (refusedCount >= 400) {
                                    handleCriticalError("Критическое отсутствие сети (400+ таймаутов). Отключение.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("9000") || lineTrim.contains("Call not found", true) -> {
                                currentHashErrorCount++
                                if (currentHashErrorCount >= 10) {
                                    handleHashError()
                                    return@forEachLine
                                }
                            }
                        }
                    }

                    if (lineTrim.contains("[CORE_METRICS]")) {
                        val msg = lineTrim.substringAfter("[CORE_METRICS]").trim()
                        if (coreTrafficMetricsUiEnabled.value) {
                            parseCoreMetrics(msg)?.let { applyCoreMetrics(it) }
                        } else {
                            currentUploadSpeedBytes.value = 0L
                            currentDownloadSpeedBytes.value = 0L
                            currentSpeedBytes.value = 0L
                            trafficGraphPoints.value = List(30) { 0f }
                            uploadTrafficGraphPoints.value = List(30) { 0f }
                            downloadTrafficGraphPoints.value = List(30) { 0f }
                        }
                        return@forEachLine
                    }

                    if (lineTrim.contains("[PROTO]")) {
                        val msg = lineTrim.substringAfter("[PROTO]").trim()
                        coreProtocolMode.value = formatCoreProtocolMode(msg)
                        return@forEachLine
                    }

                    if (lineTrim.contains("[СТАТИСТИКА]")) {
                        val msg = lineTrim.substringAfter("[СТАТИСТИКА]").trim()
                        stats.value = msg

                        val match = Regex("Активных:\\s*(\\d+)").find(msg)
                        if (match != null) {
                            activeWorkers.value = match.groupValues[1].toIntOrNull() ?: 0
                        }

                        updateLog("stats", "[СТАТИСТИКА] $msg", 3, false)
                        return@forEachLine
                    }

                    when {
                        lineTrim.contains("[КАПЧА] RJS:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] RJS:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
                            
                            val stableKey = when {
                                text.contains("Загрузка") || text.contains("fetch") -> "captcha_rjs_1"
                                text.contains("PoW") -> "captcha_rjs_2"
                                text.contains("осматривает") || text.contains("человек") -> "captcha_rjs_3"
                                text.contains("captchaNotRobot") || text.contains("Отправка") -> "captcha_rjs_4"
                                text.contains("endSession") -> "captcha_rjs_5"
                                text.contains("решена") -> "captcha_rjs_6"
                                else -> "captcha_rjs_${text.take(15).hashCode()}"
                            }
                            updateLog(stableKey, "[КАПЧА RJS] $text", 5, false)
                        }

                        lineTrim.contains("[КАПЧА] RJS-SLIDER:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] RJS-SLIDER:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()

                            val stableKey = when {
                                text.contains("Загрузка") -> "captcha_slider_1"
                                text.contains("PoW") -> "captcha_slider_2"
                                text.contains("слайдер", true) -> "captcha_slider_3"
                                text.contains("решена") -> "captcha_slider_4"
                                else -> "captcha_slider_${text.take(15).hashCode()}"
                            }
                            updateLog(stableKey, "[КАПЧА Slider] $text", 5, false)
                        }

                        lineTrim.contains("[КАПЧА] WBV:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] WBV:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
                            
                            val isErr = text.contains("Ошибка")
                            val stableKey = when {
                                text.contains("Запрос") -> "captcha_wv_step_2" 
                                text.contains("Токен") -> "captcha_wv_step_5"  
                                isErr -> "captcha_wv_err"
                                else -> "captcha_wv_go_other"
                            }
                            updateLog(stableKey, "[КАПЧА WBV] $text", 5, isErr)
                        }

                        lineTrim.contains("Старт") || lineTrim.contains("Ожидайте") ->
                            updateLog("creds_start", "[ВК] Получение учетных данных...", 2, false)
                        lineTrim.contains("Креды получены") ->
                            updateLog("creds_lifetime", lineTrim, 2, false)
                        lineTrim.contains("Креды OK") || lineTrim.contains("Первые креды") ->
                            updateLog("creds_ok", "[ВК] Учетные данные проверены ✓", 2, false)
                        lineTrim.contains("Решаю VK Smart Captcha") ->
                            updateLog("captcha_start", "[КАПЧА] Решение капчи...", 5, false)
                        lineTrim.contains("Smart Captcha решена") ->
                            updateLog("captcha_done", "[КАПЧА] Капча решена ✓", 5, false)
                        lineTrim.contains("капча не решена") || lineTrim.contains("ошибка решения капчи") ->
                            updateLog("captcha_failed", "[КАПЧА] Ошибка решения капчи", 5, true)
                        lineTrim.contains("Relay:") ->
                            updateLog("dtls_start", "[DTLS] Рукопожатие (Handshake)...", 1, false)
                        lineTrim.contains("DTLS ОК") ->
                            updateLog("dtls_ok", "[DTLS] Соединение установлено ✓", 1, false)
                        lineTrim.contains("Активна ✓") ->
                            updateLog("ready", "[READY] Туннель готов к работе ✓", 2, false)
                        
                        isError -> {
                            val errorKey = when {
                                lineTrim.contains("connection refused") -> "err_conn_refused"
                                lineTrim.contains("timeout") -> "err_timeout"
                                lineTrim.contains("кредов") -> "err_creds"
                                lineTrim.contains("DTLS") -> "err_dtls"
                                else -> "general_error_" + lineTrim.take(15).hashCode()
                            }
                            updateLog(errorKey, lineTrim, 99, true)
                        }
                    }

                    if (line.contains("╔") && line.contains("WireGuard")) {
                        collectingConfig = true
                        configBuilder.clear()
                        return@forEachLine
                    } else if (collectingConfig) {
                        if (line.contains("╚")) {
                            collectingConfig = false
                            val configStr = configBuilder.toString().trim()
                            config.value = configStr
                            activeWireGuardMtu.value = extractWireGuardConfigValue(configStr, "MTU")?.toIntOrNull() ?: 0
                            activeWireGuardDns.value = extractWireGuardConfigValue(configStr, "DNS").orEmpty()
                            
                            scope.launch(Dispatchers.Main) {
                                try {
                                    wgHelper?.startTunnel(configStr)
                                } catch (e: Exception) {
                                    updateLog("vpn_start_error", "Ошибка запуска VPN: ${e.readableMessage()}", 99, true)
                                }
                            }
                        } else if (line.contains("║")) {
                            val content = line.replace("║", "").trim()
                            if (content.isNotEmpty()) {
                                configBuilder.appendLine(content)
                            }
                        }
                        return@forEachLine
                    }
                }
            } catch (e: Exception) {
                if (!processRestartExpected && !processStopExpected) {
                    updateLog("sys_error", "Процесс остановлен: ${e.message}", -1, true)
                }
            } finally {
                if (process === readerProcess) {
                    process = null
                    coreProcessPid.value = null
                }
                if (processRestartExpected) {
                    processRestartExpected = false
                } else {
                    resetCoreMetrics()
                    running.value = false
                    activeCoreBackend.value = null
                    coreProtocolMode.value = "нет данных"
                    activeRequestedMtu.value = 0
                    activeRequestedDns.value = ""
                    activeWireGuardMtu.value = 0
                    activeWireGuardDns.value = ""
                    stats.value = "Остановлено"
                    updateWidgetState()
                }
                processStopExpected = false
            }
        }
    }

    private fun handleCriticalError(message: String) {
        updateLog("circuit_breaker", "[СТОП] $message", -1, true)
        stop()
    }

    private fun extractWireGuardConfigValue(config: String, key: String): String? {
        return config.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key =") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun handleHashError() {
        val params = currentParams ?: return
        val context = lastContext ?: return

        currentHashErrorCount = 0
        forceRegenerateUA = true

        if (params.secondaryVkHash.isNotEmpty() && activeHashIndex == 0) {
            updateLog("hash_switch", "Основной хеш мертв. Переключение на запасной...", 50, true)
            activeHashIndex = 1
            stopOnlyProcess()
            start(context, params, isSwitching = true)
        } else {
            val msg = if (activeHashIndex == 1) "Запасной хеш тоже мертв. Отключение." else "Хеш умер, запасного нет. Отключение."
            handleCriticalError(msg)
        }
    }

    private fun startWatchdog(context: Context, params: TunnelParams) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var zeroWorkersSince = 0L
            delay(10_000) 
            while (isActive && running.value) {
                val proc = process
                if (proc == null || !proc.isAlive) {
                    updateLog("watchdog", "⚠ Процесс упал. Перезапуск...", 50, true)
                    resetCoreMetrics()
                    forceRegenerateUA = true
                    killProcess(keepRunning = true)
                    delay(2000)
                    if (running.value) {
                        start(context, params, isSwitching = true)
                    }
                    return@launch 
                }

                val workers = activeWorkers.value
                if (workers <= 0) {
                    if (zeroWorkersSince == 0L) {
                        zeroWorkersSince = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - zeroWorkersSince > 90_000 && !ManlCaptchaWebViewManager.isCaptchaPending) {
                        updateLog("watchdog", "⚠ Зомби-процесс (0 воркеров 90с). Перезапуск...", 50, true)
                        forceRegenerateUA = true
                        killProcess(keepRunning = true)
                        delay(2000)
                        if (running.value) {
                            start(context, params, isSwitching = true)
                        }
                        return@launch
                    }
                } else {
                    zeroWorkersSince = 0L
                }

                delay(5_000)
            }
        }
    }

    fun restartTransport() {
        val params = currentParams ?: return
        val context = lastContext ?: return
        updateLog("network_restart", "[СЕТЬ] Перезапуск транспорта из-за смены сети...", 50, false)
        killProcess(keepRunning = true)
        scope.launch {
            delay(1500)
            start(context, params, isSwitching = true)
        }
    }

    fun consumeUserRequestedStop(): Boolean {
        val result = userRequestedStopPending
        userRequestedStopPending = false
        return result
    }

    fun pause() {
        if (!running.value) return
        killProcess(keepRunning = true)
        resetCoreMetrics()
    }

    fun resume() {
        if (currentParams != null && lastContext != null) {
            scope.launch {
                start(lastContext!!, currentParams!!, isSwitching = true)
            }
        }
    }

    private fun killProcess(keepRunning: Boolean = false, expectedStop: Boolean = false) {
        if (keepRunning) {
            processRestartExpected = true
        } else {
            processRestartExpected = false
        }
        processStopExpected = keepRunning || expectedStop
        watchdogJob?.cancel()
        readerJob?.cancel()
        metricsJob?.cancel()
        runtimeSettingsJob?.cancel()
        runtimeSettingsJob = null
        runtimeWorkerApplyJob?.cancel()
        runtimeWorkerApplyJob = null
        resetCoreMetrics()
        currentPingMs.value = 0
        val proc = process
        process = null
        coreProcessPid.value = null
        coreProtocolMode.value = if (keepRunning) "ожидание restart" else "нет данных"
        if (proc != null) {
            try { proc.destroy() } catch (_: Exception) {}
            try { proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            if (proc.isAlive) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
                try { proc.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            }
        }
        if (!keepRunning) {
            running.value = false
            activeCoreBackend.value = null
            coreProtocolMode.value = "нет данных"
            stats.value = "Остановлено"
            tunnelStartedAtElapsedMs.value = null
            activeTunnelNode.value = ActiveTunnelNode()
            updateWidgetState()
        }
    }

    private fun stopOnlyProcess() {
        killProcess(keepRunning = true)
    }

    private fun startRuntimeSettingsMonitor(settingsStore: SettingsStore, initialWorkers: Int) {
        runtimeSettingsJob?.cancel()
        runtimeSettingsJob = scope.launch(Dispatchers.IO) {
            currentParams = currentParams?.copy(workersPerHash = initialWorkers.coerceIn(1, 72))
            settingsStore.workersPerHash
                .map { it.coerceIn(1, 72) }
                .combine(settingsStore.captchaMode.map { normalizeRuntimeCaptchaMode(it) }) { workers, captchaMode ->
                    workers to captchaMode
                }
                .collectLatest { (requestedWorkers, requestedCaptchaMode) ->
                    if (requestedCaptchaMode != currentCaptchaMode) {
                        setCaptchaMode(requestedCaptchaMode)
                    }
                    val applied = currentParams?.workersPerHash?.coerceIn(1, 72)
                    if (requestedWorkers == applied) return@collectLatest
                    scheduleWorkerCountApply(requestedWorkers)
                }
        }
    }

    fun setCaptchaMode(mode: String) {
        val normalized = normalizeRuntimeCaptchaMode(mode)
        currentCaptchaMode = normalized
        currentParams = currentParams?.copy(captchaMode = normalized)
    }

    private fun normalizeRuntimeCaptchaMode(mode: String): String {
        return if (mode.equals("wv", ignoreCase = true) || mode.equals("manual", ignoreCase = true)) {
            "wv"
        } else {
            "auto"
        }
    }

    fun scheduleWorkerCountApply(workers: Int, delayMs: Long = 5_000L) {
        val clamped = workers.coerceIn(1, 72)
        runtimeWorkerApplyJob?.cancel()
        runtimeWorkerApplyJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            val params = currentParams
            if (params?.workersPerHash?.coerceIn(1, 72) == clamped) return@launch
            val ctx = lastContext ?: return@launch
            if (SettingsStore(ctx).disableRuntimeWorkerApply.first()) return@launch
            setWorkerCount(clamped)
        }
    }

    fun setWorkerCount(workers: Int): Boolean {
        val clamped = workers.coerceIn(1, 72)
        if (running.value && currentParams?.workersPerHash?.coerceIn(1, 72) == clamped) {
            return true
        }
        if (writeCoreCommand("SET_WORKERS|$clamped")) {
            currentParams = currentParams?.copy(workersPerHash = clamped)
            return true
        }
        if (running.value) {
            updateLog("runtime_workers_apply_err", "[CONTROL] Не удалось отправить SET_WORKERS|$clamped", 200, true)
        } else {
            currentParams = currentParams?.copy(workersPerHash = clamped)
        }
        return false
    }

    fun stop(userRequested: Boolean = false) {
        if (userRequested) {
            userRequestedStopPending = true
        }
        scope.launch(Dispatchers.Main) {
            wgHelper?.stopTunnel()
        }
        killProcess(expectedStop = userRequested)
        resetCoreMetrics()
        currentParams = null
        tunnelStartedAtElapsedMs.value = null
        ManlCaptchaWebViewManager.cancelCaptcha()
    }

    suspend fun stopAndWait() {
        withContext(Dispatchers.Main) {
            wgHelper?.stopTunnel()
        }
        withContext(Dispatchers.IO) {
            killProcess()
            resetCoreMetrics()
            currentParams = null
            tunnelStartedAtElapsedMs.value = null
            ManlCaptchaWebViewManager.cancelCaptcha()
            repeat(30) {
                try {
                    java.net.ServerSocket(9000, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.close() }
                    return@withContext 
                } catch (_: Exception) {
                    delay(100)
                }
            }
        }
    }

    fun reloadWireGuard() {
        if (running.value) {
            scope.launch {
                wgHelper?.reloadTunnel()
            }
        }
    }

    private suspend fun handleCaptchaSolve(requestMode: String, redirectUri: String, sessionToken: String) {
        val ctx = lastContext ?: run {
            writeCaptchaResult("error:context is null")
            return
        }
        val mode = requestMode.lowercase()

        try {
            val token = when (mode) {
                "auto" -> solveSingleAutoWebViewCaptcha(redirectUri, sessionToken)
                "manual" -> {
                    updateLog("captcha_wv_step_1", "[КАПЧА WBV] Создание ручного WebView...", 5, false)
                    ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
                else -> {
                    if (currentCaptchaMode == "auto") {
                        solveAutoWebViewCaptcha(ctx, redirectUri, sessionToken)
                    } else {
                        updateLog("captcha_wv_step_1", "[КАПЧА WBV] Создание ручного WebView...", 5, false)
                        ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                    }
                }
            }
            updateLog("captcha_wv_step_4", "[КАПЧА WBV] Капча решена ✓", 5, false)
            writeCaptchaResult(token)
        } catch (e: IllegalStateException) {
            val errorMsg = e.message ?: "WV state error"
            updateLog("captcha_wv_err", "[КАПЧА WBV] $errorMsg", 5, true)
            writeCaptchaResult("error:$errorMsg")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            updateLog("captcha_wv_err", "[КАПЧА WBV] Таймаут WebView", 5, true)
            writeCaptchaResult("error:timeout")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            updateLog("captcha_wv_err", "[КАПЧА WBV] Отменено", 5, true)
            writeCaptchaResult("error:cancelled")
        } catch (e: Exception) {
            val errorMsg = e.message ?: "${e::class.simpleName}"
            if (errorMsg != "tunnel stopped") {
                updateLog("captcha_wv_err", "[КАПЧА WBV] Ошибка — $errorMsg", 5, true)
            }
            writeCaptchaResult("error:$errorMsg")
        }

        updateLog("captcha_wv_step_6", "[КАПЧА WBV] WebView уничтожен", 5, false)
    }

    private suspend fun solveSingleAutoWebViewCaptcha(
        redirectUri: String,
        sessionToken: String
    ): String {
        updateLog("captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка 10с...", 5, false)
        return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
            updateLog("captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
        }
    }

    private suspend fun solveAutoWebViewCaptcha(
        ctx: Context,
        redirectUri: String,
        sessionToken: String
    ): String {
        for (attempt in 1..2) {
            updateLog("captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка $attempt/2...", 5, false)
            try {
                return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
                    updateLog("captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                updateLog("captcha_wv_timeout_$attempt", "[КАПЧА WBV] Авто таймаут 10с ($attempt/2)", 5, attempt == 2)
                if (attempt == 2) {
                    updateLog("captcha_wv_fallback", "[КАПЧА WBV] 2 таймаута авто, открыт ручной WebView", 5, false)
                    return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
            } catch (e: IllegalStateException) {
                if (e.message == CaptchaWebViewManager.ERROR_SLIDER_DETECTED) {
                    updateLog("captcha_wv_fallback", "[КАПЧА WBV] Обнаружен слайдер, открыт ручной WebView", 5, false)
                    return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
                throw e
            }
        }
        return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
    }

    private fun writeCaptchaResult(result: String) {
        if (writeCoreCommand("CAPTCHA_RESULT|$result")) return
        if (!running.value) return
        updateLog("captcha_write_err", "[КАПЧА] Ошибка записи результата в ядро", 200, true)
    }

    @Synchronized
    private fun writeCoreCommand(command: String): Boolean {
        val proc = process
        if (proc == null || !proc.isAlive) return false
        return try {
            val line = if (command.endsWith('\n')) command else "$command\n"
            proc.outputStream.write(line.toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
        resetCoreMetrics()
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }

    private fun Process.safePid(): Int? {
        return runCatching {
            val pidMethod = javaClass.methods.firstOrNull { it.name == "pid" && it.parameterTypes.isEmpty() }
            (pidMethod?.invoke(this) as? Long)?.toInt()
        }.getOrNull()
    }

    fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        cooldownSeconds.value = seconds
        cooldownJob = scope.launch(Dispatchers.Main) {
            while (cooldownSeconds.value > 0) {
                delay(1000)
                cooldownSeconds.update { it - 1 }
            }
        }
    }
}

data class TunnelParams(
    val peer: String,
    val vkHashes: String,
    val secondaryVkHash: String = "",
    val workersPerHash: Int,
    val port: Int,
    val sni: String = "",
    val connectionPassword: String = "",
    val protocol: String = "udp",
    val captchaMode: String = "auto",
    val wrapTransport: Boolean = false,
    val wifiHighPerformance: Boolean = true,
    val clientKeepaliveSeconds: Int = 10
)
