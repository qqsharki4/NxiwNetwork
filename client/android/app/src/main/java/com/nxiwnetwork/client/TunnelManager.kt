package com.nxiwnetwork.client

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

@Stable
data class LogEntry(
    val key: String,
    val message: String,
    val count: Int = 1,
    val priority: Int = 99, 
    val isError: Boolean = false
)

object TunnelManager {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null
    private var readerJob: Job? = null
    private var watchdogJob: Job? = null
    private var metricsJob: Job? = null
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
    private var currentCaptchaMode = "rjs"

    val running = MutableStateFlow(false)
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val unreadErrorCount = MutableStateFlow(0)
    val config = MutableStateFlow<String?>(null)
    val stats = MutableStateFlow("Ожидание данных...")
    val activeWorkers = MutableStateFlow(0)
    
    val cooldownSeconds = MutableStateFlow(0)
    private var cooldownJob: Job? = null

    val currentPingMs = MutableStateFlow(0)
    val currentSpeedBytes = MutableStateFlow(0L)

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
            var lastRxBytes = TrafficStats.getTotalRxBytes()
            var loopCount = 0
            
            while (isActive && running.value) {
                val currentRxBytes = TrafficStats.getTotalRxBytes()
                val delta = currentRxBytes - lastRxBytes
                currentSpeedBytes.value = if (delta > 0) delta else 0L
                lastRxBytes = currentRxBytes

                if (loopCount % 5 == 0) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $ip")
                            val reader = BufferedReader(InputStreamReader(process.inputStream))
                            var ping = 0
                            reader.forEachLine { line ->
                                if (line.contains("time=")) {
                                    val timeStr = line.substringAfter("time=").substringBefore(" ms")
                                    ping = timeStr.toFloatOrNull()?.toInt() ?: 0
                                }
                            }
                            process.waitFor()
                            if (ping > 0) currentPingMs.value = ping
                        } catch (e: Exception) {
                            currentPingMs.value = 0
                        }
                    }
                }
                
                delay(1000)
                loopCount++
            }
        }
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
            currentSpeedBytes.value = 0L
            currentPingMs.value = 0
        }
        
        wgHelper = WireGuardHelper(appContext)

        scope.launch {
            try {
                val targetHash = if (activeHashIndex == 0) params.vkHashes else params.secondaryVkHash
                
                val hashList = targetHash
                    .split(Regex("[,\\s\\n]+"))
                    .map { it.trim() }
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

                val binaryPath = context.applicationInfo.nativeLibraryDir + "/libclient.so"
                val binaryFile = File(binaryPath)
                
                if (!binaryFile.exists()) {
                    updateLog("binary_error", "Ошибка: Бинарный файл не найден", 99, true)
                    running.value = false
                    updateWidgetState()
                    return@launch
                }

                val peerEndpoint = normalizeNodeEndpoint(params.peer)
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

                val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
                cmd.add("-device-id")
                cmd.add(androidId)

                if (params.connectionPassword.isNotEmpty()) {
                   cmd.add("-password")
                   cmd.add(params.connectionPassword)
                }

                cmd.add(if (params.protocol == "tcp") "-tcp" else "-udp")
                cmd.add("-captcha-mode")
                cmd.add(params.captchaMode)

                val settingsStore = SettingsStore(appContext)
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
                running.value = true
                updateWidgetState()
                
                val serverIp = nodeEndpointHost(peerEndpoint).ifBlank { params.peer.substringBefore(":") }
                startMetricsMonitor(serverIp)
                startLogReader()
                startWatchdog(appContext, params)

            } catch (e: Exception) {
                updateLog("critical_start_error", "Критическая ошибка запуска: ${e.message}", 99, true)
                running.value = false
                updateWidgetState()
            }
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
                        if (currentCaptchaMode == "wv") {
                            val parts = lineTrim.substringAfter("CAPTCHA_SOLVE|").split("|", limit = 2)
                            if (parts.size == 2) {
                                val redirectUri = parts[0]
                                val sessionToken = parts[1]
                                scope.launch {
                                    handleCaptchaSolve(redirectUri, sessionToken)
                                }
                            } else {
                                writeCaptchaResult("error:invalid CAPTCHA_SOLVE format")
                            }
                        } else {
                            writeCaptchaResult("error:wv mode not enabled")
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
                }
                if (processRestartExpected) {
                    processRestartExpected = false
                } else {
                    running.value = false
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
                    activeWorkers.value = 0
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
        activeWorkers.value = 0
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
        currentSpeedBytes.value = 0L
        currentPingMs.value = 0
        val proc = process
        process = null
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
            updateWidgetState()
        }
    }

    private fun stopOnlyProcess() {
        killProcess(keepRunning = true)
    }

    fun stop(userRequested: Boolean = false) {
        if (userRequested) {
            userRequestedStopPending = true
        }
        scope.launch(Dispatchers.Main) {
            wgHelper?.stopTunnel()
        }
        killProcess(expectedStop = userRequested)
        activeWorkers.value = 0
        currentParams = null
        ManlCaptchaWebViewManager.cancelCaptcha()
    }

    suspend fun stopAndWait() {
        withContext(Dispatchers.Main) {
            wgHelper?.stopTunnel()
        }
        withContext(Dispatchers.IO) {
            killProcess()
            activeWorkers.value = 0
            currentParams = null
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

    private suspend fun handleCaptchaSolve(redirectUri: String, sessionToken: String) {
        updateLog("captcha_wv_step_1", "[КАПЧА WBV] Создание WebView...", 5, false)

        try {
            val ctx = lastContext ?: return
            val token = ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
            updateLog("captcha_wv_step_4", "[КАПЧА WBV] Капча решена ✓", 5, false)
            writeCaptchaResult(token)
        } catch (e: IllegalStateException) {
            val errorMsg = e.message ?: "WV state error"
            updateLog("captcha_wv_err", "[КАПЧА WBV] $errorMsg", 5, true)
            writeCaptchaResult("error:$errorMsg")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            updateLog("captcha_wv_err", "[КАПЧА WBV] Таймаут (45с)", 5, true)
            writeCaptchaResult("error:timeout (45s)")
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

    private fun writeCaptchaResult(result: String) {
        val proc = process
        if (proc == null || !proc.isAlive) return
        try {
            val line = "CAPTCHA_RESULT|$result\n"
            proc.outputStream.write(line.toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        } catch (e: Exception) {
            updateLog("captcha_write_err", "[КАПЧА] Ошибка записи: ${e.message}", 200, true)
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
        activeWorkers.value = 0
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
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
    val captchaMode: String = "rjs",
    val wifiHighPerformance: Boolean = true,
    val clientKeepaliveSeconds: Int = 10
)
