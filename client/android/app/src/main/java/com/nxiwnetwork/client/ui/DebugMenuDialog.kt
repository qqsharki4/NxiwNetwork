package com.nxiwnetwork.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nxiwnetwork.client.AppDiagnostics
import com.nxiwnetwork.client.AppDiagnosticsSnapshot
import com.nxiwnetwork.client.AndroidPingSchedule
import com.nxiwnetwork.client.BatteryDiagnostics
import com.nxiwnetwork.client.CoreBackend
import com.nxiwnetwork.client.CoreTrafficMetrics
import com.nxiwnetwork.client.ProcessDiagnostics
import com.nxiwnetwork.client.SettingsStore
import com.nxiwnetwork.client.StorageDiagnostics
import com.nxiwnetwork.client.TunnelManager
import com.nxiwnetwork.client.TrafficDiagnostics
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun DebugMenuDialog(appVersionName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val settingsStore = remember(appContext) { SettingsStore(appContext) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val selectedBackendId by settingsStore.coreBackend.collectAsStateWithLifecycle("go")
    val protocol by settingsStore.protocol.collectAsStateWithLifecycle("udp")
    val workers by settingsStore.workersPerHash.collectAsStateWithLifecycle(12)
    val keepaliveSeconds by settingsStore.clientKeepaliveSeconds.collectAsStateWithLifecycle(10)
    val customMtu by settingsStore.customMtu.collectAsStateWithLifecycle(0)
    val dnsMode by settingsStore.customDns.collectAsStateWithLifecycle("default")
    val customDnsIp by settingsStore.customDnsIp.collectAsStateWithLifecycle("1.1.1.1")
    val routingEnabled by settingsStore.routingEnabled.collectAsStateWithLifecycle(true)
    val isWhitelist by settingsStore.isWhitelist.collectAsStateWithLifecycle(false)
    val showSystemApps by settingsStore.showSystemApps.collectAsStateWithLifecycle(false)
    val wifiHighPerformance by settingsStore.wifiHighPerformance.collectAsStateWithLifecycle(true)
    val autoConnectOnBoot by settingsStore.autoConnectOnBoot.collectAsStateWithLifecycle(false)
    val useDynamicColor by settingsStore.useDynamicColor.collectAsStateWithLifecycle(true)
    val updateChannel by settingsStore.updateChannel.collectAsStateWithLifecycle("stable")
    val diagnosticsEnabled by settingsStore.diagnosticsEnabled.collectAsStateWithLifecycle(false)
    val diagnosticsProcessMetrics by settingsStore.diagnosticsProcessMetrics.collectAsStateWithLifecycle(true)
    val diagnosticsTrafficMetrics by settingsStore.diagnosticsTrafficMetrics.collectAsStateWithLifecycle(true)
    val diagnosticsBatteryMetrics by settingsStore.diagnosticsBatteryMetrics.collectAsStateWithLifecycle(true)
    val dashboardWidgetsRaw by settingsStore.dashboardWidgets.collectAsStateWithLifecycle(SettingsStore.DEFAULT_DASHBOARD_WIDGETS)
    val coreTrafficMetricsUi by settingsStore.coreTrafficMetricsUi.collectAsStateWithLifecycle(true)
    val pingMetricsUi by settingsStore.pingMetricsUi.collectAsStateWithLifecycle(true)
    val savedAndroidPingHomeIntervalMs by settingsStore.androidPingHomeIntervalMs.collectAsStateWithLifecycle(SettingsStore.DEFAULT_ANDROID_PING_HOME_INTERVAL_MS)
    val savedAndroidPingBackgroundIntervalMs by settingsStore.androidPingBackgroundIntervalMs.collectAsStateWithLifecycle(SettingsStore.DEFAULT_ANDROID_PING_BACKGROUND_INTERVAL_MS)
    val speedMetricModeRaw by settingsStore.speedMetricMode.collectAsStateWithLifecycle(SettingsStore.DEFAULT_SPEED_METRIC_MODE)
    val graphSpeedMetricModeRaw by settingsStore.graphSpeedMetricMode.collectAsStateWithLifecycle(SettingsStore.DEFAULT_SPEED_METRIC_MODE)
    val manualCaptchaOverlay by settingsStore.manualCaptchaOverlay.collectAsStateWithLifecycle(false)
    val disableRuntimeWorkerApply by settingsStore.disableRuntimeWorkerApply.collectAsStateWithLifecycle(false)
    val suppressBatteryOptimizationPrompt by settingsStore.suppressBatteryOptimizationPrompt.collectAsStateWithLifecycle(false)
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val activeBackend by TunnelManager.activeCoreBackend.collectAsStateWithLifecycle()
    val coreProtocolMode by TunnelManager.coreProtocolMode.collectAsStateWithLifecycle()
    val activeRequestedMtu by TunnelManager.activeRequestedMtu.collectAsStateWithLifecycle()
    val activeRequestedDns by TunnelManager.activeRequestedDns.collectAsStateWithLifecycle()
    val activeWireGuardMtu by TunnelManager.activeWireGuardMtu.collectAsStateWithLifecycle()
    val activeWireGuardDns by TunnelManager.activeWireGuardDns.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()
    val coreTrafficMetrics by TunnelManager.coreTrafficMetrics.collectAsStateWithLifecycle()
    val stats by TunnelManager.stats.collectAsStateWithLifecycle()
    val pingMs by TunnelManager.currentPingMs.collectAsStateWithLifecycle()
    val androidPingSchedule by TunnelManager.androidPingSchedule.collectAsStateWithLifecycle()
    val speedBytes by TunnelManager.currentSpeedBytes.collectAsStateWithLifecycle()
    val logs by TunnelManager.logs.collectAsStateWithLifecycle()
    val unreadErrors by TunnelManager.unreadErrorCount.collectAsStateWithLifecycle()
    val diagnostics by AppDiagnostics.snapshot.collectAsStateWithLifecycle()

    LaunchedEffect(appContext) {
        AppDiagnostics.start(appContext)
    }

    LaunchedEffect(savedAndroidPingHomeIntervalMs, savedAndroidPingBackgroundIntervalMs) {
        TunnelManager.setAndroidPingSchedule(savedAndroidPingHomeIntervalMs, savedAndroidPingBackgroundIntervalMs)
    }

    val versionCode = remember(appContext) { readAppVersionCode(appContext) }
    val selectedBackend = CoreBackend.fromId(selectedBackendId)
    val deviceName = remember {
        listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "unknown" }
    }
    val dnsLabel = if (dnsMode == "custom") "$dnsMode ($customDnsIp)" else dnsMode
    val dashboardWidgets = remember(dashboardWidgetsRaw) { parseDashboardWidgets(dashboardWidgetsRaw) }
    val speedMetricMode = parseSpeedMetricMode(speedMetricModeRaw)
    val graphSpeedMetricMode = parseSpeedMetricMode(graphSpeedMetricModeRaw)
    var overlayPermissionRefresh by remember { mutableIntStateOf(0) }
    val overlayPermissionGranted = remember(overlayPermissionRefresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || AndroidSettings.canDrawOverlays(appContext)
    }

    fun copyDebugText(label: String, text: String, toast: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }

    fun openSystemIntent(intent: Intent, errorText: String) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show() }
    }

    fun setDashboardWidget(widget: WidgetType, enabled: Boolean) {
        val updated = if (enabled) {
            (dashboardWidgets + widget).distinct()
        } else {
            dashboardWidgets - widget
        }
        scope.launch { settingsStore.saveDashboardWidgets(encodeDashboardWidgets(updated)) }
    }

    val snapshot = remember(
        appVersionName,
        versionCode,
        selectedBackend,
        protocol,
        workers,
        keepaliveSeconds,
        customMtu,
        dnsLabel,
        tunnelRunning,
        activeBackend,
        coreProtocolMode,
        activeWorkers,
        coreTrafficMetrics,
        stats,
        pingMs,
        speedBytes,
        logs.size,
        unreadErrors,
        routingEnabled,
        isWhitelist,
        showSystemApps,
        wifiHighPerformance,
        autoConnectOnBoot,
        useDynamicColor,
        updateChannel,
        dashboardWidgetsRaw,
        coreTrafficMetricsUi,
        pingMetricsUi,
        androidPingSchedule,
        speedMetricModeRaw,
        graphSpeedMetricModeRaw,
        manualCaptchaOverlay,
        disableRuntimeWorkerApply,
        suppressBatteryOptimizationPrompt,
        overlayPermissionGranted,
        diagnostics
    ) {
        buildDebugSnapshot(
            context = appContext,
            appVersionName = appVersionName,
            versionCode = versionCode,
            deviceName = deviceName,
            selectedBackend = selectedBackend.label,
            activeBackend = activeBackend?.label ?: "нет",
            coreProtocolMode = coreProtocolMode,
            protocol = protocol.uppercase(),
            workers = workers,
            keepaliveSeconds = keepaliveSeconds,
            customMtu = customMtu,
            dnsLabel = dnsLabel,
            tunnelRunning = tunnelRunning,
            activeWorkers = activeWorkers,
            coreTrafficMetrics = coreTrafficMetrics,
            pingMs = pingMs,
            speedBytes = speedBytes,
            stats = stats,
            logCount = logs.size,
            unreadErrors = unreadErrors,
            routingEnabled = routingEnabled,
            isWhitelist = isWhitelist,
            showSystemApps = showSystemApps,
            wifiHighPerformance = wifiHighPerformance,
            autoConnectOnBoot = autoConnectOnBoot,
            useDynamicColor = useDynamicColor,
            updateChannel = updateChannel,
            dashboardWidgets = dashboardWidgets,
            coreTrafficMetricsUi = coreTrafficMetricsUi,
            pingMetricsUi = pingMetricsUi,
            androidPingSchedule = androidPingSchedule,
            speedMetricMode = speedMetricMode,
            graphSpeedMetricMode = graphSpeedMetricMode,
            manualCaptchaOverlay = manualCaptchaOverlay,
            disableRuntimeWorkerApply = disableRuntimeWorkerApply,
            suppressBatteryOptimizationPrompt = suppressBatteryOptimizationPrompt,
            overlayPermissionGranted = overlayPermissionGranted,
            diagnostics = diagnostics
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BugReport, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Debug tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("NxiwNetwork $appVersionName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DebugSectionTitle("Состояние")
                    DebugInfoRow("Версия", "$appVersionName ($versionCode)")
                    DebugInfoRow("Пакет", appContext.packageName)
                    DebugInfoRow("Устройство", deviceName)
                    DebugInfoRow("Android", "${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
                    DebugInfoRow("Туннель", if (tunnelRunning) "запущен" else "остановлен")
                    DebugInfoRow("Ядро", "${selectedBackend.label} выбрано / ${activeBackend?.label ?: "нет"} активно")
                    DebugInfoRow("Протокол ноды", coreProtocolMode)
                    DebugNodePolicyAlert(
                        coreProtocolMode = coreProtocolMode,
                        activeRequestedMtu = activeRequestedMtu,
                        activeRequestedDns = activeRequestedDns,
                        activeWireGuardMtu = activeWireGuardMtu,
                        activeWireGuardDns = activeWireGuardDns
                    )
                    DebugInfoRow("Транспорт", protocol.uppercase())
                    DebugInfoRow("Потоки", "$workers настроено / $activeWorkers активно")
                    DebugInfoRow("Keepalive", "$keepaliveSeconds сек")
                    DebugInfoRow("MTU", formatActiveMtu(customMtu, activeRequestedMtu, activeWireGuardMtu, tunnelRunning))
                    DebugInfoRow("DNS", formatActiveDns(dnsLabel, activeRequestedDns, activeWireGuardDns, tunnelRunning))
                    DebugInfoRow("Ping", if (pingMs > 0) "$pingMs ms" else "нет данных")
                    DebugInfoRow("Скорость", formatDebugSpeed(speedBytes))
                    DebugInfoRow("Статистика", stats)
                    DebugInfoRow("Дропы ядра", formatDropMetrics(coreTrafficMetrics))
                    DebugInfoRow("Логи", "${logs.size} записей, ошибок непрочитано: $unreadErrors")

                    DebugSectionTitle("Метрики приложения")
                    DebugInfoRow("Сбор", if (diagnostics.enabled) "включен" else "выключен")
                    if (diagnostics.enabled) {
                        DebugInfoRow("Uptime", formatDiagnosticsUptime(diagnostics))
                        DebugInfoRow("RAM app", formatAppMemory(diagnostics))
                        DebugInfoRow("CPU app", formatProcessCpu(diagnostics.appProcess))
                        DebugInfoRow("RAM/CPU core", formatCoreProcess(diagnostics.coreProcess))
                        DebugInfoRow("UID трафик", formatTraffic(diagnostics.traffic))
                        DebugInfoRow("Батарея", formatBattery(diagnostics.battery))
                        DebugInfoRow("Хранилище", formatStorage(diagnostics.storage))
                    } else {
                        DebugInfoRow("Статус", "Постоянный сбор выключен. Включи тумблер ниже, и данные продолжат собираться в фоне.")
                    }

                    DebugSectionTitle("Сбор метрик")
                    DebugSwitchRow("Постоянно собирать метрики", "Сбор идет в фоне внутри процесса приложения, пока включен.", diagnosticsEnabled) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        AppDiagnostics.start(appContext)
                        scope.launch { settingsStore.saveDiagnosticsEnabled(enabled) }
                    }
                    DebugSwitchRow("CPU/RAM процессов", "App process + core process: CPU time, live %, PSS, heap, threads.", diagnosticsProcessMetrics) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveDiagnosticsProcessMetrics(enabled) }
                    }
                    DebugSwitchRow("UID трафик", "RX/TX всего приложения по Android UID, включая сервис и native core.", diagnosticsTrafficMetrics) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveDiagnosticsTrafficMetrics(enabled) }
                    }
                    DebugSwitchRow("Батарея", "Заряд, температура, напряжение, ток, источник питания если устройство отдает эти данные.", diagnosticsBatteryMetrics) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveDiagnosticsBatteryMetrics(enabled) }
                    }

                    DebugSectionTitle("Виджеты главной")
                    DebugSwitchRow("Core traffic metrics в UI", "Если выключить, приложение перестанет прокидывать скорость/график из ядра в интерфейс. Ядро продолжит считать метрики.", coreTrafficMetricsUi) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        TunnelManager.setCoreTrafficMetricsUiEnabled(enabled)
                        scope.launch { settingsStore.saveCoreTrafficMetricsUi(enabled) }
                    }
                    DebugSwitchRow("Ping-метрика", "Если выключить, приложение перестанет делать системный ping до ноды для виджета пинга.", pingMetricsUi) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        TunnelManager.setPingMetricsEnabled(enabled)
                        scope.launch { settingsStore.savePingMetricsUi(enabled) }
                    }
                    DebugInfoRow("Android ping scheduler", formatAndroidPingSchedule(pingMetricsUi, androidPingSchedule))
                    DebugPingIntervalRow(
                        title = "Ping на Главной",
                        description = "Интервал системного ping, когда открыт главный экран.",
                        valueMs = androidPingSchedule.homeIntervalMs,
                        minMs = SettingsStore.MIN_ANDROID_PING_HOME_INTERVAL_MS,
                        maxMs = SettingsStore.MAX_ANDROID_PING_HOME_INTERVAL_MS,
                        stepMs = 100,
                        enabled = pingMetricsUi,
                        onRuntimeChange = { value ->
                            TunnelManager.setAndroidPingSchedule(value, androidPingSchedule.backgroundIntervalMs)
                        },
                        onCommit = { value ->
                            scope.launch { settingsStore.saveAndroidPingHomeIntervalMs(value) }
                        }
                    )
                    DebugPingIntervalRow(
                        title = "Ping в фоне",
                        description = "Интервал ping, когда экран включён, но Главная не открыта.",
                        valueMs = androidPingSchedule.backgroundIntervalMs,
                        minMs = SettingsStore.MIN_ANDROID_PING_BACKGROUND_INTERVAL_MS,
                        maxMs = SettingsStore.MAX_ANDROID_PING_BACKGROUND_INTERVAL_MS,
                        stepMs = 60_000,
                        enabled = pingMetricsUi,
                        onRuntimeChange = { value ->
                            TunnelManager.setAndroidPingSchedule(androidPingSchedule.homeIntervalMs, value)
                        },
                        onCommit = { value ->
                            scope.launch { settingsStore.saveAndroidPingBackgroundIntervalMs(value) }
                        }
                    )
                    DebugToolButton(Icons.Default.Restore, "Сбросить Android ping", "Вернёт 1.6 сек на Главной и 5 мин в фоне.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        TunnelManager.setAndroidPingSchedule(
                            SettingsStore.DEFAULT_ANDROID_PING_HOME_INTERVAL_MS,
                            SettingsStore.DEFAULT_ANDROID_PING_BACKGROUND_INTERVAL_MS
                        )
                        scope.launch {
                            settingsStore.saveAndroidPingHomeIntervalMs(SettingsStore.DEFAULT_ANDROID_PING_HOME_INTERVAL_MS)
                            settingsStore.saveAndroidPingBackgroundIntervalMs(SettingsStore.DEFAULT_ANDROID_PING_BACKGROUND_INTERVAL_MS)
                        }
                    }
                    DebugSpeedMetricModeRow("Что показывает виджет скорости", "Режим для карточки «Скорость».", speedMetricMode) { mode ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveSpeedMetricMode(mode.id) }
                    }
                    DebugSpeedMetricModeRow("Что показывает график скорости", "Режим для линии графика «Трафик сети».", graphSpeedMetricMode) { mode ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveGraphSpeedMetricMode(mode.id) }
                    }
                    WidgetType.entries.filter { it.isUserWidget }.forEach { widget ->
                        DebugSwitchRow(widget.title, "Показывать виджет на главном экране.", dashboardWidgets.contains(widget)) { enabled ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            setDashboardWidget(widget, enabled)
                        }
                    }

                    DebugSectionTitle("Капча")
                    DebugSwitchRow("WebView поверх приложений", "Devtools-флаг. Если включен и Android-разрешение выдано, ручная капча откроется overlay-окном поверх других приложений.", manualCaptchaOverlay) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveManualCaptchaOverlay(enabled) }
                    }
                    DebugToolButton(
                        Icons.Default.OpenInNew,
                        "Разрешение поверх окон",
                        if (overlayPermissionGranted) "Разрешение уже выдано." else "Откроет системный экран «Показывать поверх других приложений»."
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        overlayPermissionRefresh++
                        openSystemIntent(
                            Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${appContext.packageName}")),
                            "Не удалось открыть настройки overlay-разрешения"
                        )
                    }

                    DebugSectionTitle("Переключатели")
                    DebugSwitchRow("Роутинг", "Пересобирает WireGuard-конфиг, если туннель активен.", routingEnabled) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            settingsStore.saveRoutingEnabled(enabled)
                            TunnelManager.reloadWireGuard()
                        }
                    }
                    DebugSwitchRow(
                        if (isWhitelist) "Режим роутинга: БС" else "Режим роутинга: ЧС",
                        "Белый/черный список приложений. При активном роутинге делает reload WireGuard.",
                        isWhitelist
                    ) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            settingsStore.saveIsWhitelist(enabled)
                            if (routingEnabled) TunnelManager.reloadWireGuard()
                        }
                    }
                    DebugSwitchRow("Системные приложения в роутинге", "Включает системные приложения в список выбора.", showSystemApps) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveShowSystemApps(enabled) }
                    }
                    DebugSwitchRow("Wi-Fi high performance", "Настройка для следующего старта сервиса туннеля.", wifiHighPerformance) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveWifiHighPerformance(enabled) }
                    }
                    DebugSwitchRow("Автоподключение после загрузки", "Включает старт туннеля через boot receiver.", autoConnectOnBoot) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveAutoConnect(enabled) }
                    }
                    DebugSwitchRow("Dynamic color", "Материальные цвета от обоев Android.", useDynamicColor) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveDynamicColor(enabled) }
                    }

                    DebugSectionTitle("Devtools")
                    DebugSwitchRow("Не делать авто применение в рантайме", "Если включено, слайдер потоков сохраняет настройку, но не отправляет SET_WORKERS в работающее ядро.", disableRuntimeWorkerApply) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveDisableRuntimeWorkerApply(enabled) }
                    }
                    DebugSwitchRow("Не просить отключить экономию батареи", "Если включено, приложение не будет автоматически открывать системный запрос оптимизации батареи при старте.", suppressBatteryOptimizationPrompt) { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveSuppressBatteryOptimizationPrompt(enabled) }
                    }
                    DebugNodePolicyPreview()
                    DebugWavyProgressPreview()
                    DebugMaterial3ExpressivePreview()
                    DebugToolButton(Icons.Default.ContentCopy, "Копировать debug snapshot", "Копирует состояние приложения без хешей и паролей.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        copyDebugText("NxiwNetwork Debug", snapshot, "Снимок отладки скопирован")
                    }
                    DebugToolButton(Icons.Default.Article, "Копировать логи", "Копирует текущий список логов.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val text = logs.joinToString("\n") { "${it.message} (x${it.count})" }
                        copyDebugText("NxiwNetwork Logs", text.ifBlank { "Логи пустые" }, "Логи скопированы")
                    }
                    DebugToolButton(Icons.Default.DeleteOutline, "Очистить логи", "Очищает видимый лог и счетчик активных воркеров.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        TunnelManager.clearLogs()
                        Toast.makeText(context, "Логи очищены", Toast.LENGTH_SHORT).show()
                    }
                    DebugToolButton(Icons.Default.MarkEmailRead, "Сбросить счетчик ошибок", "Обнуляет непрочитанные ошибки в интерфейсе.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        TunnelManager.clearUnreadErrors()
                        Toast.makeText(context, "Счетчик ошибок сброшен", Toast.LENGTH_SHORT).show()
                    }
                    DebugToolButton(Icons.Default.Refresh, "Reload WireGuard", "Принудительно применяет текущий WireGuard-конфиг.", enabled = tunnelRunning) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        TunnelManager.reloadWireGuard()
                        Toast.makeText(context, "WireGuard reload запущен", Toast.LENGTH_SHORT).show()
                    }
                    DebugToolButton(Icons.Default.RestartAlt, "Перезапустить транспорт", "Перезапускает только core-транспорт без остановки VPN-сервиса.", enabled = tunnelRunning) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        TunnelManager.restartTransport()
                        Toast.makeText(context, "Транспорт перезапускается", Toast.LENGTH_SHORT).show()
                    }
                    DebugToolButton(Icons.Default.NetworkPing, "Сбросить MTU в Авто", "Ставит MTU=Авто и делает WireGuard reload.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            settingsStore.saveCustomMtu(0)
                            TunnelManager.reloadWireGuard()
                            Toast.makeText(context, "MTU сброшен в Авто", Toast.LENGTH_SHORT).show()
                        }
                    }
                    DebugToolButton(Icons.Default.Fingerprint, "Сбросить User-Agent", "Следующий старт туннеля сгенерирует новый UA.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            settingsStore.saveUserAgent("")
                            Toast.makeText(context, "User-Agent сброшен", Toast.LENGTH_SHORT).show()
                        }
                    }
                    DebugToolButton(Icons.Default.Update, "Сбросить update rate-limit", "Позволяет сразу снова проверить обновления.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            settingsStore.saveUpdateRateLimitUntil(0L)
                            settingsStore.saveUpdateStatus("Debug: rate-limit проверки обновлений сброшен")
                            Toast.makeText(context, "Rate-limit обновлений сброшен", Toast.LENGTH_SHORT).show()
                        }
                    }
                    DebugToolButton(Icons.Default.NewReleases, "Сбросить skip/later обновлений", "Очищает пропуск и отложенные обновления текущего канала.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            settingsStore.saveSkippedUpdateTag(updateChannel, "")
                            settingsStore.saveUpdateLaterUntil(updateChannel, 0L)
                            settingsStore.saveRemoteChangelogSeenKey(updateChannel, "")
                            settingsStore.saveStartupChangelogSeenKey(updateChannel, "")
                            settingsStore.saveUpdateStatus("Debug: skip/later обновлений сброшены")
                            Toast.makeText(context, "Skip/later сброшены", Toast.LENGTH_SHORT).show()
                        }
                    }
                    DebugToolButton(Icons.Default.Settings, "Системные настройки приложения", "Открывает Android App info.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        openSystemIntent(
                            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}")),
                            "Не удалось открыть настройки приложения"
                        )
                    }
                    DebugToolButton(Icons.Default.VpnLock, "Системные VPN-настройки", "Открывает экран VPN в Android.") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        openSystemIntent(Intent(AndroidSettings.ACTION_VPN_SETTINGS), "Не удалось открыть VPN-настройки")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                Spacer(Modifier.height(10.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(20.dp)) {
                    Text("Закрыть", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DebugNodePolicyPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ReportProblem, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Node policy preview",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }
        DebugPolicyPreviewRow("Клиент", "MTU 1500 / DNS 8.8.8.8")
        DebugPolicyPreviewRow("Лимит ноды", "MTU 1280-1420 / custom DNS on")
        DebugPolicyPreviewRow("Применено", "MTU 1420 / DNS 8.8.8.8")
        Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
            contentColor = MaterialTheme.colorScheme.error,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "MTU зажат сервером до верхней границы policy.",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun DebugNodePolicyAlert(
    coreProtocolMode: String,
    activeRequestedMtu: Int,
    activeRequestedDns: String,
    activeWireGuardMtu: Int,
    activeWireGuardDns: String
) {
    val protocolAppliedMtu = remember(coreProtocolMode) { extractAppliedMtu(coreProtocolMode) }
    val protocolAppliedDns = remember(coreProtocolMode) { extractAppliedDns(coreProtocolMode).orEmpty() }
    val appliedMtu = activeWireGuardMtu.takeIf { it > 0 } ?: protocolAppliedMtu
    val appliedDns = activeWireGuardDns.ifBlank { protocolAppliedDns }
    val policyMtu = remember(coreProtocolMode) { extractPolicyMtu(coreProtocolMode) }
    val requestedDns = activeRequestedDns.trim()
    val mtuMismatch = activeRequestedMtu > 0 && appliedMtu != null && appliedMtu != activeRequestedMtu
    val dnsMismatch = requestedDns.isNotEmpty() &&
        appliedDns.isNotEmpty() &&
        normalizeDnsForCompare(appliedDns) != normalizeDnsForCompare(requestedDns)
    if (!mtuMismatch && !dnsMismatch) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Policy mismatch", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        if (mtuMismatch) {
            Text(
                "MTU: сессия запросила $activeRequestedMtu, фактически $appliedMtu${policyMtu?.let { " / лимит $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                lineHeight = 16.sp
            )
        }
        if (dnsMismatch) {
            Text(
                "DNS: сессия запросила $requestedDns, фактически $appliedDns",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                lineHeight = 16.sp
            )
        }
    }
}

private fun formatActiveMtu(configuredMtu: Int, requestedMtu: Int, activeMtu: Int, tunnelRunning: Boolean): String {
    val configured = if (configuredMtu == 0) "авто" else configuredMtu.toString()
    if (!tunnelRunning || activeMtu <= 0) return configured
    val requested = if (requestedMtu == 0) "авто" else requestedMtu.toString()
    return "факт $activeMtu / сессия $requested / настройки $configured"
}

private fun formatActiveDns(configuredDns: String, requestedDns: String, activeDns: String, tunnelRunning: Boolean): String {
    if (!tunnelRunning || activeDns.isBlank()) return configuredDns
    val requested = requestedDns.ifBlank { "default" }
    return "факт $activeDns / сессия $requested / настройки $configuredDns"
}

private fun normalizeDnsForCompare(raw: String): String {
    return raw.split(',', ';', ' ', '\t', '\n', '\r')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(",")
}

@Composable
private fun DebugPolicyPreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}

private fun extractAppliedMtu(raw: String): Int? {
    return Regex("""MTU\s+(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private fun extractAppliedDns(raw: String): String? {
    return Regex("""DNS\s+([^,\s/]+)""").find(raw)?.groupValues?.getOrNull(1)?.trim()
}

private fun extractPolicyMtu(raw: String): String? {
    return Regex("""mtu:([0-9]+-[0-9]+)""").find(raw)?.groupValues?.getOrNull(1)
}

private fun snapPingIntervalMs(raw: Float, minMs: Int, maxMs: Int, stepMs: Int): Int {
    val relative = (raw.roundToInt() - minMs).coerceAtLeast(0)
    val snapped = minMs + ((relative + stepMs / 2) / stepMs) * stepMs
    return snapped.coerceIn(minMs, maxMs)
}

private fun formatPingInterval(ms: Int): String {
    return if (ms < 60_000) {
        String.format("%.1f сек", ms / 1000f)
    } else {
        val minutes = ms / 60_000
        "$minutes мин"
    }
}

private fun formatAndroidPingSchedule(enabled: Boolean, schedule: AndroidPingSchedule): String {
    if (!enabled) return "выключен"
    return "Главная ${formatPingInterval(schedule.homeIntervalMs)} / фон ${formatPingInterval(schedule.backgroundIntervalMs)} / экран off: off"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DebugMaterial3ExpressivePreview() {
    var tonalChecked by remember { mutableStateOf(true) }
    var outlinedChecked by remember { mutableStateOf(false) }
    var chipGo by remember { mutableStateOf(true) }
    var chipRust by remember { mutableStateOf(false) }
    var chipUdp by remember { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Material3 expressive preview",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {},
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight)
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(ButtonDefaults.MediumIconSize))
                Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
                Text("Primary")
            }
            ElevatedButton(onClick = {}, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Elevated")
            }
            FilledTonalButton(onClick = {}, shapes = ButtonDefaults.shapes()) {
                Text("Tonal")
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TonalToggleButton(
                checked = tonalChecked,
                onCheckedChange = { tonalChecked = it },
                shapes = ToggleButtonDefaults.shapes()
            ) {
                Icon(Icons.Default.Tune, null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                Text("Tonal toggle")
            }
            OutlinedToggleButton(
                checked = outlinedChecked,
                onCheckedChange = { outlinedChecked = it },
                shapes = ToggleButtonDefaults.shapes()
            ) {
                Icon(Icons.Default.Route, null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                Text("Outlined")
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ElevatedFilterChip(
                selected = chipGo,
                onClick = { chipGo = !chipGo },
                label = { Text("Go") },
                leadingIcon = { Icon(Icons.Default.Memory, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                shapes = FilterChipDefaults.shapes()
            )
            ElevatedFilterChip(
                selected = chipRust,
                onClick = { chipRust = !chipRust },
                label = { Text("Rust") },
                leadingIcon = { Icon(Icons.Default.Bolt, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                shapes = FilterChipDefaults.shapes()
            )
            FilterChip(
                selected = chipUdp,
                onClick = { chipUdp = !chipUdp },
                label = { Text("UDP") },
                trailingIcon = { Icon(Icons.Default.SyncAlt, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                shapes = FilterChipDefaults.shapes()
            )
        }

        Box {
            OutlinedButton(onClick = { menuExpanded = true }, shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Expressive menu")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Preview action") },
                    onClick = { menuExpanded = false },
                    leadingIcon = { Icon(Icons.Default.Visibility, null) }
                )
                DropdownMenuItem(
                    text = { Text("Toggle style") },
                    onClick = { menuExpanded = false },
                    leadingIcon = { Icon(Icons.Default.Animation, null) },
                    trailingIcon = { Icon(Icons.Default.Check, null) }
                )
                DropdownMenuItem(
                    text = { Text("No-op item") },
                    onClick = { menuExpanded = false },
                    leadingIcon = { Icon(Icons.Default.Code, null) }
                )
            }
        }

        CompositionLocalProvider(
            LocalRippleConfiguration provides RippleConfiguration(
                focus = RippleConfiguration.Focus.InsetRing(
                    outerStrokeColor = colorScheme.primary,
                    innerStrokeColor = colorScheme.surface
                )
            )
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = {}, shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Default.CenterFocusStrong, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Inset focus")
                }
                TextButton(onClick = {}, shape = RoundedCornerShape(18.dp)) {
                    Text("Focus ripple")
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(176.dp)) {
            FloatingActionButtonMenu(
                expanded = fabExpanded,
                modifier = Modifier.align(Alignment.BottomEnd),
                button = {
                    ToggleFloatingActionButton(
                        checked = fabExpanded,
                        onCheckedChange = { fabExpanded = it }
                    ) {
                        Icon(
                            imageVector = if (checkedProgress > 0.5f) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = null
                        )
                    }
                }
            ) {
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false },
                    icon = { Icon(Icons.Default.ContentCopy, null) },
                    text = { Text("Copy") }
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false },
                    icon = { Icon(Icons.Default.Refresh, null) },
                    text = { Text("Reload") }
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false },
                    icon = { Icon(Icons.Default.BugReport, null) },
                    text = { Text("Debug") }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DebugWavyProgressPreview() {
    val progress = remember { Animatable(0f) }
    var indeterminate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            indeterminate = false
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 3200, easing = LinearEasing)
            )
            indeterminate = true
            delay(1800)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            "Wavy progress preview",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        if (indeterminate) {
            LinearWavyProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(14.dp),
                amplitude = 1f
            )
        } else {
            LinearWavyProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.fillMaxWidth().height(14.dp),
                amplitude = { value -> WavyProgressIndicatorDefaults.indicatorAmplitude(value) }
            )
        }
    }
}

@Composable
private fun DebugInfoRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DebugSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun DebugSwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DebugPingIntervalRow(
    title: String,
    description: String,
    valueMs: Int,
    minMs: Int,
    maxMs: Int,
    stepMs: Int,
    enabled: Boolean,
    onRuntimeChange: (Int) -> Unit,
    onCommit: (Int) -> Unit
) {
    var sliderValue by remember(valueMs) { mutableFloatStateOf(valueMs.toFloat()) }
    val snappedValue = snapPingIntervalMs(sliderValue, minMs, maxMs, stepMs)
    val steps = (((maxMs - minMs) / stepMs) - 1).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
            }
            Text(
                formatPingInterval(snappedValue),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { raw ->
                val snapped = snapPingIntervalMs(raw, minMs, maxMs, stepMs)
                sliderValue = snapped.toFloat()
                onRuntimeChange(snapped)
            },
            onValueChangeFinished = {
                onCommit(snapPingIntervalMs(sliderValue, minMs, maxMs, stepMs))
            },
            enabled = enabled,
            valueRange = minMs.toFloat()..maxMs.toFloat(),
            steps = steps
        )
    }
}

@Composable
private fun DebugSpeedMetricModeRow(
    title: String,
    description: String,
    selectedMode: SpeedMetricMode,
    onSelected: (SpeedMetricMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedMetricMode.entries.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = { onSelected(mode) },
                    label = { Text(mode.chipLabel) }
                )
            }
        }
    }
}

@Composable
private fun DebugToolButton(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                lineHeight = 16.sp
            )
        }
    }
}

private fun buildDebugSnapshot(
    context: Context,
    appVersionName: String,
    versionCode: Long,
    deviceName: String,
    selectedBackend: String,
    activeBackend: String,
    coreProtocolMode: String,
    protocol: String,
    workers: Int,
    keepaliveSeconds: Int,
    customMtu: Int,
    dnsLabel: String,
    tunnelRunning: Boolean,
    activeWorkers: Int,
    coreTrafficMetrics: CoreTrafficMetrics,
    pingMs: Int,
    speedBytes: Long,
    stats: String,
    logCount: Int,
    unreadErrors: Int,
    routingEnabled: Boolean,
    isWhitelist: Boolean,
    showSystemApps: Boolean,
    wifiHighPerformance: Boolean,
    autoConnectOnBoot: Boolean,
    useDynamicColor: Boolean,
    updateChannel: String,
    dashboardWidgets: List<WidgetType>,
    coreTrafficMetricsUi: Boolean,
    pingMetricsUi: Boolean,
    androidPingSchedule: AndroidPingSchedule,
    speedMetricMode: SpeedMetricMode,
    graphSpeedMetricMode: SpeedMetricMode,
    manualCaptchaOverlay: Boolean,
    disableRuntimeWorkerApply: Boolean,
    suppressBatteryOptimizationPrompt: Boolean,
    overlayPermissionGranted: Boolean,
    diagnostics: AppDiagnosticsSnapshot
): String = buildString {
    appendLine("NxiwNetwork Debug")
    appendLine("Version: $appVersionName ($versionCode)")
    appendLine("Package: ${context.packageName}")
    appendLine("Device: $deviceName")
    appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
    appendLine("Tunnel: ${if (tunnelRunning) "running" else "stopped"}")
    appendLine("Core: selected=$selectedBackend active=$activeBackend")
    appendLine("Node protocol: $coreProtocolMode")
    appendLine("Transport: $protocol")
    appendLine("Workers: configured=$workers active=$activeWorkers")
    appendLine("Keepalive: ${keepaliveSeconds}s")
    appendLine("MTU: ${if (customMtu == 0) "auto" else customMtu}")
    appendLine("DNS: $dnsLabel")
    appendLine("Ping: ${if (pingMs > 0) "$pingMs ms" else "n/a"}")
    appendLine("Speed: ${formatDebugSpeed(speedBytes)}")
    appendLine("Stats: $stats")
    appendLine("Core drops: ${formatDropMetrics(coreTrafficMetrics)}")
    appendLine("Logs: count=$logCount unread_errors=$unreadErrors")
    appendLine("Routing: enabled=$routingEnabled mode=${if (isWhitelist) "whitelist" else "blacklist"} show_system=$showSystemApps")
    appendLine("WiFi high performance: $wifiHighPerformance")
    appendLine("Auto-connect on boot: $autoConnectOnBoot")
    appendLine("Dynamic color: $useDynamicColor")
    appendLine("Update channel: $updateChannel")
    appendLine("Dashboard widgets: ${dashboardWidgets.joinToString(",") { it.name }}")
    appendLine("Core traffic metrics UI: $coreTrafficMetricsUi")
    appendLine("Ping metrics UI: $pingMetricsUi")
    appendLine("Android ping schedule: ${formatAndroidPingSchedule(pingMetricsUi, androidPingSchedule)}")
    appendLine("Speed metric mode: ${speedMetricMode.id}")
    appendLine("Graph speed metric mode: ${graphSpeedMetricMode.id}")
    appendLine("Manual captcha overlay: enabled=$manualCaptchaOverlay permission=$overlayPermissionGranted")
    appendLine("Runtime worker apply disabled: $disableRuntimeWorkerApply")
    appendLine("Battery optimization prompt suppressed: $suppressBatteryOptimizationPrompt")
    appendLine("Diagnostics enabled: ${diagnostics.enabled}")
    appendLine("Diagnostics uptime: ${formatDiagnosticsUptime(diagnostics)}")
    appendLine("Diagnostics app memory: ${formatAppMemory(diagnostics)}")
    appendLine("Diagnostics app CPU: ${formatProcessCpu(diagnostics.appProcess)}")
    appendLine("Diagnostics core: ${formatCoreProcess(diagnostics.coreProcess)}")
    appendLine("Diagnostics UID traffic: ${formatTraffic(diagnostics.traffic)}")
    appendLine("Diagnostics battery: ${formatBattery(diagnostics.battery)}")
    appendLine("Diagnostics storage: ${formatStorage(diagnostics.storage)}")
}

private fun formatDebugSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0L) return "0 KB/s"
    val kb = bytesPerSecond / 1024f
    return if (kb >= 1024f) String.format("%.1f MB/s", kb / 1024f) else String.format("%.0f KB/s", kb)
}

private fun formatDropMetrics(metrics: CoreTrafficMetrics): String {
    if (metrics.droppedPackets <= 0L) return "0"
    return "${metrics.droppedPackets} | no_workers=${metrics.droppedNoWorkers}, queue=${metrics.droppedWorkerQueue}, no_client=${metrics.droppedNoClient}, local_write=${metrics.droppedLocalWrite}"
}

private fun formatDiagnosticsUptime(snapshot: AppDiagnosticsSnapshot): String {
    val tunnel = snapshot.tunnelUptimeMs?.let { formatDuration(it) } ?: "нет"
    return "app ${formatDuration(snapshot.appProcessUptimeMs)} | tunnel $tunnel | CPU cores: ${snapshot.cpuCoreCount}"
}

private fun formatAppMemory(snapshot: AppDiagnosticsSnapshot): String {
    val process = snapshot.appProcess
    val pss = process.pssKb?.let { formatBytes(it * 1024L) } ?: "нет данных"
    val privateDirty = process.privateDirtyKb?.let { formatBytes(it * 1024L) } ?: "нет данных"
    val native = process.nativePssKb?.let { formatBytes(it * 1024L) } ?: "нет данных"
    val dalvik = process.dalvikPssKb?.let { formatBytes(it * 1024L) } ?: "нет данных"
    return "PSS $pss | private $privateDirty | native $native | dalvik $dalvik | heap ${formatBytes(snapshot.javaHeapUsedBytes)} / ${formatBytes(snapshot.javaHeapMaxBytes)}"
}

private fun formatProcessCpu(process: ProcessDiagnostics): String {
    if (process.pid == null) return "нет процесса"
    val live = process.cpuPercent?.let { String.format("%.1f%%", it) } ?: "считается"
    val total = process.cpuTimeMs?.let { formatDuration(it) } ?: "нет данных"
    val threads = process.threadCount?.toString() ?: "?"
    return "pid ${process.pid} | live $live | total $total | threads $threads"
}

private fun formatCoreProcess(process: ProcessDiagnostics): String {
    if (process.pid == null) return "core не запущен или PID недоступен"
    val memory = process.pssKb?.let { formatBytes(it * 1024L) } ?: "нет RAM"
    return "${formatProcessCpu(process)} | PSS $memory"
}

private fun formatTraffic(traffic: TrafficDiagnostics): String {
    val rx = traffic.uidRxBytes?.let { formatBytes(it) } ?: "нет данных"
    val tx = traffic.uidTxBytes?.let { formatBytes(it) } ?: "нет данных"
    val rxRate = traffic.rxBytesPerSecond?.let { formatRate(it) } ?: "считается"
    val txRate = traffic.txBytesPerSecond?.let { formatRate(it) } ?: "считается"
    return "RX $rx ($rxRate) | TX $tx ($txRate)"
}

private fun formatBattery(battery: BatteryDiagnostics): String {
    val level = battery.levelPercent?.let { "$it%" } ?: "нет %"
    val current = battery.currentMa?.let { "${it} mA" } ?: "ток недоступен"
    val temp = battery.temperatureC?.let { String.format("%.1f°C", it) } ?: "temp недоступна"
    val voltage = battery.voltageMv?.let { "${it} mV" } ?: "voltage недоступен"
    val charge = battery.chargeMah?.let { "$it mAh" } ?: "capacity недоступна"
    val charging = when (battery.isCharging) {
        true -> "заряжается"
        false -> "разряд"
        null -> "статус неизвестен"
    }
    return "$level | $charging/${battery.plugged ?: "?"} | $current | $temp | $voltage | $charge"
}

private fun formatStorage(storage: StorageDiagnostics): String {
    return "data free ${formatBytes(storage.dataFreeBytes)} | usable ${formatBytes(storage.dataUsableBytes)} | total ${formatBytes(storage.dataTotalBytes)}"
}

private fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

private fun formatBytes(bytes: Long): String {
    val abs = kotlin.math.abs(bytes.toDouble())
    return when {
        abs >= 1024.0 * 1024.0 * 1024.0 -> String.format("%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
        abs >= 1024.0 * 1024.0 -> String.format("%.2f МБ", bytes / (1024.0 * 1024.0))
        abs >= 1024.0 -> String.format("%.1f КБ", bytes / 1024.0)
        else -> "$bytes Б"
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun readAppVersionCode(context: Context): Long {
    return try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    } catch (_: Exception) {
        0L
    }
}
