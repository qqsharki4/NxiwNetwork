package com.nxiwnetwork.client

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nxiwnetwork.client.ui.LogsTab
import com.nxiwnetwork.client.ui.SettingsTab
import com.nxiwnetwork.client.ui.DeployTab
import com.nxiwnetwork.client.ui.ExceptionsTab
import com.nxiwnetwork.client.ui.ChangelogDialog
import com.nxiwnetwork.client.ui.InfoTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.absoluteValue

private const val UPDATE_REMIND_LATER_MS = 24L * 60L * 60L * 1000L
private const val AUTO_UPDATE_CHECK_INTERVAL_MS = 3L * 60L * 60L * 1000L

class MainActivity : ComponentActivity() {

    private var pendingVpnStartIntent: Intent? = null
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val startIntent = pendingVpnStartIntent
        pendingVpnStartIntent = null
        if (startIntent != null && (result.resultCode == RESULT_OK || VpnService.prepare(this) == null)) {
            startTunnelService(startIntent)
        }
    }
    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { checkAndRequestBattery() }
    private var permissionFlowStarted = false
    private var expectedDisconnect = false
    private var connectedTime = 0L
    private val pendingImportConfig = mutableStateOf<ImportNodeConfig?>(null)

    companion object {
        var activeActivities = 0
        var isForeground: Boolean
            get() = activeActivities > 0
            set(value) {}
    }

    override fun onStart() {
        super.onStart()
        activeActivities++
        ManlCaptchaWebViewManager.checkAndShowPendingCaptcha(this)
    }

    override fun onStop() {
        super.onStop()
        activeActivities--
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()

        if (!permissionFlowStarted) {
            permissionFlowStarted = true
            checkAndRequestNotifications()
        }
        setupDynamicShortcuts()

        val settingsStore = SettingsStore(this)
        lifecycleScope.launch(Dispatchers.IO) {
            if (settingsStore.diagnosticsEnabled.first()) {
                AppDiagnostics.start(applicationContext)
            }
        }
        val prefs = getSharedPreferences("nxiwnetwork_prefs", Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("is_first_launch", true)
        val startupChangelogKey = buildChangelogSeenKey()
        val startupChangelogChannel = readCurrentAppChannelKey()

        handleIntents(intent, settingsStore)

        lifecycleScope.launch {
            TunnelManager.running.collect { isRunning ->
                if (isRunning) {
                    connectedTime = System.currentTimeMillis()
                    expectedDisconnect = false
                } else {
                    val stoppedByUser = TunnelManager.consumeUserRequestedStop()
                    if (!expectedDisconnect && !stoppedByUser && connectedTime > 0 && (System.currentTimeMillis() - connectedTime) > 5000) {
                        showConnectionDropNotification()
                    }
                    expectedDisconnect = false
                    connectedTime = 0L
                }
            }
        }

        setContent {
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle("system")
            val dynamicColor by settingsStore.useDynamicColor.collectAsStateWithLifecycle(true)
            var showOnboarding by remember { mutableStateOf(isFirstLaunch) }
            var showStartupChangelog by remember { mutableStateOf(false) }
            var startupChangelogMarkdown by remember { mutableStateOf<String?>(null) }
            var pendingUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
            var pendingUpdateChannel by remember { mutableStateOf("stable") }
            var startupUpdateChecked by remember { mutableStateOf(false) }
            val downloadState by UpdateCheckCoordinator.downloadState.collectAsStateWithLifecycle()
            val currentAppVersionName = remember { readCurrentAppVersionName() }

            LaunchedEffect(showOnboarding) {
                if (showOnboarding) return@LaunchedEffect

                val previousVersionName = settingsStore.getLastInstalledVersion(startupChangelogChannel).ifBlank { null }
                val lastSeenKey = settingsStore.getStartupChangelogSeenKey(startupChangelogChannel)
                startupChangelogMarkdown = buildStartupChangelogMarkdown(previousVersionName, currentAppVersionName)
                showStartupChangelog = lastSeenKey != startupChangelogKey && !startupChangelogMarkdown.isNullOrBlank()
                settingsStore.saveLastInstalledVersion(startupChangelogChannel, currentAppVersionName)
                if (startupUpdateChecked) return@LaunchedEffect
                startupUpdateChecked = true

                var hasCachedUpdateResult = settingsStore.hasCachedAvailableUpdates()
                val rawCachedUpdates = settingsStore.getCachedAvailableUpdates()
                val cachedUpdates = ReleaseUpdater.filterNewerThanInstalled(this@MainActivity, rawCachedUpdates)
                if (cachedUpdates.size != rawCachedUpdates.size) {
                    settingsStore.saveCachedAvailableUpdates(cachedUpdates)
                    if (cachedUpdates.isEmpty()) settingsStore.saveUpdateStatus("Обновлений нет")
                }
                UpdateCheckCoordinator.setAvailableUpdates(cachedUpdates)
                var canShowStartupUpdateDialog = true
                var lastAutomaticCheckAt = settingsStore.updateLastCheckAt.first()
                while (true) {
                    val normalizedChannel = normalizeUpdateChannelId(settingsStore.updateChannel.first())
                    pendingUpdateChannel = normalizedChannel
                    val now = System.currentTimeMillis()
                    val currentUpdates = UpdateCheckCoordinator.availableUpdates.value
                    val skippedTagForStartup = settingsStore.getSkippedUpdateTag(normalizedChannel).ifBlank { null }
                    val visibleCachedUpdates = currentUpdates.filter { ReleaseUpdater.updateMatchesChannel(it, normalizedChannel) }
                    val cachedDialogUpdate = visibleCachedUpdates
                        .firstOrNull { it.tagName != skippedTagForStartup }

                    if (
                        canShowStartupUpdateDialog &&
                        pendingUpdate == null &&
                        cachedDialogUpdate != null &&
                        now >= settingsStore.getUpdateLaterUntil(normalizedChannel)
                    ) {
                        pendingUpdate = cachedDialogUpdate
                    }

                    if (
                        !hasCachedUpdateResult ||
                        lastAutomaticCheckAt <= 0L ||
                        now - lastAutomaticCheckAt >= AUTO_UPDATE_CHECK_INTERVAL_MS
                    ) {
                        val allowDialogForThisCheck = canShowStartupUpdateDialog
                        lastAutomaticCheckAt = now
                        val skippedTag = skippedTagForStartup
                        val updateResult = runCatching {
                            ReleaseUpdater.checkForUpdates(this@MainActivity, "dev")
                        }
                        updateResult.fold(
                            onSuccess = { updates ->
                                val visibleUpdates = updates
                                    .filter { ReleaseUpdater.updateMatchesChannel(it, normalizedChannel) }
                                val latestUpdate = visibleUpdates.firstOrNull()
                                val startupDialogUpdate = visibleUpdates.firstOrNull { it.tagName != skippedTag }
                                settingsStore.saveUpdateRateLimitUntil(0L)
                                settingsStore.saveUpdateCheckStatus(latestUpdate?.let { "Доступна версия ${it.tagName}" } ?: "Обновлений нет")
                                settingsStore.saveCachedAvailableUpdates(updates)
                                hasCachedUpdateResult = true
                                UpdateCheckCoordinator.setAvailableUpdates(updates)
                                if (
                                    allowDialogForThisCheck &&
                                    startupDialogUpdate != null &&
                                    now >= settingsStore.getUpdateLaterUntil(normalizedChannel)
                                ) {
                                    pendingUpdate = startupDialogUpdate
                                }
                            },
                            onFailure = { error ->
                                val rateLimit = error as? GitHubRateLimitException
                                if (rateLimit != null) {
                                    settingsStore.saveUpdateRateLimitUntil(rateLimit.resetAtMillis ?: fallbackRateLimitUntil())
                                } else {
                                    settingsStore.saveUpdateRateLimitUntil(0L)
                                }
                                settingsStore.saveUpdateStatus(ReleaseUpdater.describeCheckFailure(error))
                            }
                        )
                    }
                    canShowStartupUpdateDialog = false

                    val elapsedSinceCheck = (System.currentTimeMillis() - lastAutomaticCheckAt).coerceAtLeast(0L)
                    delay((AUTO_UPDATE_CHECK_INTERVAL_MS - elapsedSinceCheck).coerceAtLeast(60_000L))
                }
            }

            NxiwTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                    
                    AnimatedVisibility(
                        visible = showOnboarding,
                        enter = fadeIn(tween(500)) + scaleIn(initialScale = 0.9f, animationSpec = tween(500)),
                        exit = fadeOut(tween(500)) + scaleOut(targetScale = 1.1f, animationSpec = tween(500))
                    ) {
                        OnboardingOverlay {
                            showOnboarding = false
                            prefs.edit().putBoolean("is_first_launch", false).apply()
                        }
                    }

                    if (showStartupChangelog && pendingImportConfig.value == null) {
                        ChangelogDialog(markdownOverride = startupChangelogMarkdown) {
                            showStartupChangelog = false
                            lifecycleScope.launch {
                                settingsStore.saveStartupChangelogSeenKey(startupChangelogChannel, startupChangelogKey)
                            }
                        }
                    }

                    pendingImportConfig.value?.let { config ->
                        ImportNodePreviewDialog(
                            initialConfig = config,
                            onDismiss = { pendingImportConfig.value = null },
                            onSave = { updated, select ->
                                lifecycleScope.launch {
                                    saveImportedNode(settingsStore, updated, select)
                                    pendingImportConfig.value = null
                                    Toast.makeText(
                                        this@MainActivity,
                                        if (select) "Нода сохранена и выбрана" else "Нода сохранена",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }

                    pendingUpdate?.let { update ->
                        if (!showOnboarding && !showStartupChangelog && pendingImportConfig.value == null) {
                            UpdateAvailableDialog(
                                update = update,
                                currentVersionName = currentAppVersionName,
                                isDownloaded = ReleaseUpdater.isUpdateDownloaded(this@MainActivity, update),
                                downloadState = downloadState,
                                onClose = {
                                    pendingUpdate = null
                                },
                                onLater = {
                                    lifecycleScope.launch {
                                        settingsStore.saveRemoteChangelogSeenKey(pendingUpdateChannel, buildRemoteChangelogSeenKey(update))
                                        settingsStore.saveUpdateLaterUntil(
                                            pendingUpdateChannel,
                                            System.currentTimeMillis() + UPDATE_REMIND_LATER_MS
                                        )
                                        settingsStore.saveUpdateStatus("Обновление ${update.tagName} отложено")
                                    }
                                    pendingUpdate = null
                                },
                                onSkip = {
                                    lifecycleScope.launch {
                                        settingsStore.saveRemoteChangelogSeenKey(pendingUpdateChannel, buildRemoteChangelogSeenKey(update))
                                        settingsStore.saveSkippedUpdateTag(pendingUpdateChannel, update.tagName)
                                        settingsStore.saveUpdateStatus("Пропущена версия ${update.tagName}")
                                    }
                                    pendingUpdate = null
                                },
                                onDownload = {
                                    lifecycleScope.launch {
                                        settingsStore.saveRemoteChangelogSeenKey(pendingUpdateChannel, buildRemoteChangelogSeenKey(update))
                                        downloadUpdate(settingsStore, update)
                                    }
                                },
                                onInstall = {
                                    lifecycleScope.launch {
                                        installDownloadedUpdate(update)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntents(intent, SettingsStore(this))
    }

    private suspend fun downloadUpdate(settingsStore: SettingsStore, update: AvailableUpdate) {
        settingsStore.saveUpdateStatus("Скачиваем ${update.tagName}...")
        UpdateCheckCoordinator.setDownloadProgress(update.tagName, 0, "Скачано 0%")
        val result = runCatching {
            ReleaseUpdater.downloadUpdateFile(this, update) { progress ->
                UpdateCheckCoordinator.setDownloadProgress(
                    tagName = update.tagName,
                    progressPercent = progress,
                    message = progress?.let { "Скачано $it%" } ?: "Скачиваем APK..."
                )
            }
        }
        settingsStore.saveUpdateStatus(
            if (result.isSuccess) "APK ${update.tagName} скачан" else "Ошибка скачивания ${update.tagName}"
        )
        UpdateCheckCoordinator.finishDownload(
            update.tagName,
            if (result.isSuccess) "APK скачан" else "Ошибка скачивания"
        )
    }

    private suspend fun installDownloadedUpdate(update: AvailableUpdate) {
        if (!ReleaseUpdater.isUpdateDownloaded(this, update)) {
            return
        }
        if (!ReleaseUpdater.canInstallDownloadedApks(this)) {
            ReleaseUpdater.openInstallPermissionSettings(this)
            return
        }

        runCatching {
            ReleaseUpdater.installDownloadedApk(this, ReleaseUpdater.downloadedUpdateFile(this, update))
        }
    }

    private fun readCurrentAppVersionName(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return packageInfo.versionName ?: "unknown"
    }

    private fun readCurrentAppChannelKey(): String {
        return parseUpdateChannel(readCurrentAppVersionName()).name.lowercase()
    }

    private fun normalizeUpdateChannelId(channel: String): String {
        return when (channel.lowercase()) {
            "pre", "dev" -> channel.lowercase()
            else -> "stable"
        }
    }

    private fun buildRemoteChangelogSeenKey(update: AvailableUpdate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(update.body.toByteArray()).joinToString("") { "%02x".format(it) }
        return "${update.tagName}:$hash"
    }

    private fun buildStartupChangelogMarkdown(previousVersionName: String?, currentVersionName: String): String {
        return runCatching {
            val changelog = assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
            filterChangelogForUpdateRange(changelog, previousVersionName, currentVersionName)
        }.getOrDefault("")
    }

    private fun buildChangelogSeenKey(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val versionName = packageInfo.versionName ?: "unknown"
        val visibleChangelogHash = runCatching {
            val changelog = assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
            val visibleChangelog = filterChangelogForVersion(changelog, versionName)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(visibleChangelog.toByteArray()).joinToString("") { "%02x".format(it) }
        }.getOrElse { "missing" }
        return "$versionName:$versionCode:$visibleChangelogHash"
    }

    private fun fallbackRateLimitUntil(): Long {
        return System.currentTimeMillis() + 60L * 60L * 1000L
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("drop_alerts", "Разрывы соединения", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Уведомления о неожиданных разрывах VPN"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showConnectionDropNotification() {
        val intent = Intent(this, MainActivity::class.java).apply { action = "com.nxiwnetwork.client.ACTION_CONNECT" }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "drop_alerts")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Защита отключена!")
            .setContentText("Соединение с туннелем было разорвано.")
            .setColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            .addAction(android.R.drawable.ic_menu_rotate, "Переподключиться", pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(999, notification)
    }

    private fun setupDynamicShortcuts() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val sm = getSystemService(ShortcutManager::class.java)
            val connect = ShortcutInfo.Builder(this, "connect_vpn")
                .setShortLabel("Подключить")
                .setIcon(Icon.createWithResource(this, android.R.drawable.ic_secure))
                .setIntent(Intent(this, MainActivity::class.java).apply { action = "com.nxiwnetwork.client.ACTION_CONNECT" })
                .build()
            val disconnect = ShortcutInfo.Builder(this, "disconnect_vpn")
                .setShortLabel("Отключить")
                .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel))
                .setIntent(Intent(this, MainActivity::class.java).apply { action = "com.nxiwnetwork.client.ACTION_DISCONNECT" })
                .build()
            sm.dynamicShortcuts = listOf(connect, disconnect)
        }
    }

    private fun handleIntents(intent: Intent, settingsStore: SettingsStore) {
        when (intent.action) {
            "com.nxiwnetwork.client.ACTION_CONNECT" -> {
                lifecycleScope.launch {
                    val peer = settingsStore.peer.first()
                    val hashes = settingsStore.vkHashes.first()
                    if (peer.isNotBlank() && hashes.isNotBlank()) {
                        val startIntent = Intent(applicationContext, TunnelService::class.java).apply {
                            action = "START"
                            putExtra("peer", normalizeNodeEndpoint(peer))
                            putExtra("vk_hashes", hashes)
	                            putExtra("workers_per_hash", settingsStore.workersPerHash.first())
	                            putExtra("port", settingsStore.listenPort.first())
	                            putExtra("connection_password", settingsStore.connectionPassword.first())
	                            putExtra("protocol", settingsStore.protocol.first())
	                            putExtra("captcha_mode", settingsStore.captchaMode.first())
	                            putExtra("wifi_high_performance", settingsStore.wifiHighPerformance.first())
	                            putExtra("client_keepalive_seconds", settingsStore.clientKeepaliveSeconds.first())
	                        }
                        startTunnelServiceWithPermission(startIntent)
                    }
                }
            }
            "com.nxiwnetwork.client.ACTION_DISCONNECT" -> {
                expectedDisconnect = true
                startService(Intent(this, TunnelService::class.java).apply { action = "STOP" })
            }
        }
        val data = intent.data ?: return
        if ((data.scheme == "nxiwnetwork" || data.scheme == "nxiw") && data.host == "config") {
            val base64Data = data.getQueryParameter("data") ?: return
            try {
                val json = JSONObject(String(Base64.decode(base64Data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
                pendingImportConfig.value = parseImportNodeConfig(json)
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка чтения ссылки", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else checkAndRequestBattery()
        } else checkAndRequestBattery()
    }

    private fun checkAndRequestBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun startTunnelServiceWithPermission(startIntent: Intent) {
        try {
            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent != null) {
                pendingVpnStartIntent = startIntent
                vpnLauncher.launch(permissionIntent)
            } else {
                startTunnelService(startIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startTunnelService(startIntent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(startIntent) else startService(startIntent)
    }

    private suspend fun saveImportedNode(settingsStore: SettingsStore, config: ImportNodeConfig, select: Boolean) {
        withContext(Dispatchers.IO) {
            val currentArray = try {
                JSONArray(settingsStore.savedServersJson.first())
            } catch (_: Exception) {
                JSONArray()
            }

            var existingIndex = -1
            for (index in 0 until currentArray.length()) {
                if (normalizeNodeEndpoint(currentArray.getJSONObject(index).optString("ip").trim()) == normalizeNodeEndpoint(formatNodeAddress(config.host, config.port))) {
                    existingIndex = index
                    break
                }
            }

            val id = if (existingIndex != -1) {
                currentArray.getJSONObject(existingIndex).optString("id", config.id)
            } else {
                config.id
            }
            val nodeJson = JSONObject().apply {
                put("id", id)
                put("name", config.name.trim())
                put("ip", formatNodeAddress(config.host, config.port))
                put("password", config.password.trim())
                put("port", config.port)
                put("protocol", config.protocol)
            }

            if (existingIndex != -1) currentArray.put(existingIndex, nodeJson) else currentArray.put(nodeJson)
            settingsStore.saveServersList(currentArray.toString())

            if (select) {
                settingsStore.save(
                    peer = formatNodeAddress(config.host, config.port),
                    vkHashes = settingsStore.vkHashes.first(),
                    secondaryVkHash = settingsStore.secondaryVkHash.first(),
                    workersPerHash = settingsStore.workersPerHash.first(),
                    protocol = config.protocol,
                    listenPort = settingsStore.listenPort.first(),
                    sni = settingsStore.sni.first()
                )
                settingsStore.saveConnectionPassword(config.password.trim())
            }
        }
    }
}

data class ImportNodeConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val password: String,
    val port: Int = 56000,
    val protocol: String = "udp"
)

private fun parseImportNodeConfig(json: JSONObject): ImportNodeConfig {
    val rawHost = listOf(
        json.optString("host", ""),
        json.optString("ip", ""),
        json.optString("peer", "")
    ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    val parsedAddress = parseNodeAddress(rawHost)
    val inferredPort = parsedAddress?.port
    val host = parsedAddress?.host?.trim().orEmpty()
    if (host.isBlank()) error("host is blank")

    val protocol = json.optString(
        "protocol",
        if (json.optBoolean("tcp", false)) "tcp" else "udp"
    ).lowercase().let { if (it == "tcp") "tcp" else "udp" }

    return ImportNodeConfig(
        id = json.optString("id", UUID.randomUUID().toString()),
        name = json.optString("name", "Импортированная нода").trim().ifBlank { "Импортированная нода" },
        host = host,
        password = json.optString("password", json.optString("pass", "")).trim(),
        port = json.optInt("port", inferredPort ?: 56000).coerceIn(1, 65535),
        protocol = protocol
    )
}

@Composable
fun UpdateAvailableDialog(
    update: AvailableUpdate,
    currentVersionName: String,
    isDownloaded: Boolean,
    downloadState: UpdateDownloadState?,
    onClose: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val activeDownload = downloadState?.takeIf { it.tagName == update.tagName }
    val isDownloading = activeDownload?.isActive == true
    val shownProgress = activeDownload?.progressPercent ?: if (isDownloaded) 100 else 0
    val animatedProgress by animateFloatAsState(
        targetValue = shownProgress.coerceIn(0, 100) / 100f,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "update_download_progress"
    )
    val dialogMaxHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceAtLeast(360.dp)
    Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .heightIn(max = dialogMaxHeight)
                .animateContentSize(animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Доступно обновление", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                currentVersionName,
                                style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                            Text(
                                "  →  ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                update.tagName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Surface(
                        modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(update.releaseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                formatUpdatePublishedAgo(update.publishedAtMillis),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp
                            )
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                DownloadApkProgressIndicator(
                                    progress = animatedProgress,
                                    isActive = isDownloading,
                                    isIndeterminate = isDownloading && activeDownload?.progressPercent == null,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Text("Что изменилось", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    ReleaseNotesContent(update.body.ifBlank { "- Нет чейнджлога к этому обновлению." })
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = if (isDownloaded && !isDownloading) onInstall else onDownload,
                    enabled = !isDownloading,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(
                        if (isDownloaded) Icons.Default.InstallMobile else Icons.Default.Download,
                        null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isDownloading -> "Скачиваем..."
                            isDownloaded -> "Установить"
                            else -> "Скачать"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Закрыть", maxLines = 1) }
                    TextButton(onClick = onLater, modifier = Modifier.weight(1f)) { Text("Позже", maxLines = 1) }
                }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Пропустить обновление", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun DownloadApkProgressIndicator(
    progress: Float,
    isActive: Boolean,
    isIndeterminate: Boolean,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val trackColor = colorScheme.surfaceVariant.copy(alpha = 0.64f)
    val progressColor = colorScheme.primary
    val waveColor = colorScheme.onPrimary.copy(alpha = 0.82f)
    val indeterminateWaveColor = colorScheme.primary
    val transition = rememberInfiniteTransition(label = "apk_download_wave")
    val waveOffset by transition.animateFloat(
        initialValue = -0.55f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "apk_download_wave_offset"
    )

    Canvas(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(999.dp))
    ) {
        val corner = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = corner
        )

        val filledWidth = if (isIndeterminate) {
            0f
        } else {
            (size.width * progress.coerceIn(0f, 1f)).coerceAtLeast(if (progress > 0f) size.height else 0f)
        }
        if (filledWidth > 0f) {
            drawRoundRect(
                color = progressColor,
                size = Size(filledWidth, size.height),
                cornerRadius = corner
            )
        }

        if (isActive) {
            val waveWidth = size.width * if (isIndeterminate) 0.38f else 0.28f
            val startX = (size.width + waveWidth) * waveOffset - waveWidth
            val endX = startX + waveWidth
            val clipRight = if (isIndeterminate) size.width else filledWidth
            if (clipRight > 0f) {
                clipRect(left = 0f, top = 0f, right = clipRight, bottom = size.height) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.42f to if (isIndeterminate) indeterminateWaveColor.copy(alpha = 0.18f) else waveColor.copy(alpha = 0.10f),
                            0.5f to if (isIndeterminate) indeterminateWaveColor else waveColor,
                            0.58f to if (isIndeterminate) indeterminateWaveColor.copy(alpha = 0.18f) else waveColor.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                            startX = startX,
                            endX = endX
                        ),
                        topLeft = Offset(startX, 0f),
                        size = Size(waveWidth, size.height),
                        cornerRadius = corner
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesContent(markdown: String) {
    markdown.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        when {
            line.isBlank() -> Spacer(Modifier.height(6.dp))
            line.startsWith("#") -> {
                Text(
                    line.trimStart('#').trim(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
                )
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                Text(
                    "• ${line.drop(2).trim()}",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            else -> {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
    }
}

@Composable
fun ImportNodePreviewDialog(
    initialConfig: ImportNodeConfig,
    onDismiss: () -> Unit,
    onSave: (ImportNodeConfig, Boolean) -> Unit
) {
    var name by remember(initialConfig.id) { mutableStateOf(initialConfig.name) }
    var host by remember(initialConfig.id) { mutableStateOf(initialConfig.host) }
    var password by remember(initialConfig.id) { mutableStateOf(initialConfig.password) }
    var portText by remember(initialConfig.id) { mutableStateOf(initialConfig.port.toString()) }
    var protocol by remember(initialConfig.id) { mutableStateOf(initialConfig.protocol) }
    var checkNonce by remember(initialConfig.id) { mutableIntStateOf(0) }
    var checkText by remember(initialConfig.id) { mutableStateOf("Проверяем ноду...") }
    var checkOk by remember(initialConfig.id) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(initialConfig.id, checkNonce) {
        checkOk = null
        checkText = "Проверяем ноду..."
        val port = portText.toIntOrNull() ?: 56000
        val result = testImportedNode(host, port)
        checkOk = result.first
        checkText = result.second
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Предпросмотр ноды", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Проверь параметры перед сохранением", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Закрыть") }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = when (checkOk) {
                        true -> MaterialTheme.colorScheme.primaryContainer
                        false -> MaterialTheme.colorScheme.errorContainer
                        null -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (checkOk) {
                            true -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            false -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            null -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(checkText, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { checkNonce++ }) { Icon(Icons.Default.Refresh, contentDescription = "Проверить снова") }
                    }
                }

                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                OutlinedTextField(value = host, onValueChange = { host = it.filter { c -> !c.isWhitespace() } }, label = { Text("Host / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Порт") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1.4f).align(Alignment.CenterVertically)) {
                        SegmentedButton(selected = protocol == "udp", onClick = { protocol = "udp" }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("UDP") }
                        SegmentedButton(selected = protocol == "tcp", onClick = { protocol = "tcp" }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("TCP") }
                    }
                }
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Пароль туннеля") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))

                val updated = ImportNodeConfig(
                    id = initialConfig.id,
                    name = name.ifBlank { "Импортированная нода" },
                    host = host.trim(),
                    password = password,
                    port = (portText.toIntOrNull() ?: 56000).coerceIn(1, 65535),
                    protocol = protocol
                )
                val canSave = updated.host.isNotBlank()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onSave(updated, false) }, enabled = canSave, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("Сохранить")
                    }
                    Button(onClick = { onSave(updated, true) }, enabled = canSave, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("Выбрать")
                    }
                }
            }
        }
    }
}

private suspend fun testImportedNode(host: String, port: Int): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    if (host.isBlank()) return@withContext false to "Host пустой"
    try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2200)
        }
        true to "TCP-проверка: $host:$port отвечает"
    } catch (e: Exception) {
        false to "TCP-проверка не прошла: ${e.message ?: e.javaClass.simpleName}"
    }
}

data class NavItem(val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector)

val navItems = listOf(
    NavItem("Главная", Icons.Filled.Home, Icons.Outlined.Home),
    NavItem("Ноды", Icons.Filled.Cloud, Icons.Outlined.Cloud),
    NavItem("Роутинг", Icons.Filled.FilterList, Icons.Outlined.FilterList),
    NavItem("Логи", Icons.Filled.Terminal, Icons.Outlined.Terminal),
    NavItem("Настройки", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(onPageChanged: (Int) -> Unit = {}) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { navItems.size })
    val unreadErrors by TunnelManager.unreadErrorCount.collectAsStateWithLifecycle()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
        if (pagerState.currentPage == 3) TunnelManager.clearUnreadErrors()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            StretchyNavigationBar(
                items = navItems,
                selectedIndex = pagerState.currentPage,
                onItemSelected = { index ->
                    if (pagerState.currentPage != index) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { pagerState.animateScrollToPage(index, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)) }
                        if (index == 3) TunnelManager.clearUnreadErrors()
                    }
                },
                unreadErrors = unreadErrors,
                tunnelRunning = tunnelRunning
            )
        }
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding), beyondViewportPageCount = 2) { page ->
            Box(modifier = Modifier.graphicsLayer {
                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                alpha = 1f - (pageOffset.coerceIn(0f, 1f) * 0.5f)
                scaleX = 1f - (pageOffset.coerceIn(0f, 1f) * 0.05f)
                scaleY = 1f - (pageOffset.coerceIn(0f, 1f) * 0.05f)
            }) {
                when (page) {
                    0 -> SettingsTab()
                    1 -> DeployTab()
                    2 -> ExceptionsTab()
                    3 -> LogsTab()
                    4 -> InfoTab()
                }
            }
        }
    }
}

@Composable
fun StretchyNavigationBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    unreadErrors: Int,
    tunnelRunning: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .fillMaxWidth()
                .height(80.dp)
        ) {
            val tabWidth = maxWidth / items.size
            val indicatorHalfWidth = if (tabWidth < 68.dp) {
                (tabWidth / 2 - 6.dp).coerceAtLeast(22.dp)
            } else {
                32.dp
            }
            val transition = updateTransition(targetState = selectedIndex, label = "tab_transition")

            val leftEdge by transition.animateDp(
                transitionSpec = {
                    if (targetState > initialState) spring(dampingRatio = 0.65f, stiffness = 150f) 
                    else spring(dampingRatio = 0.65f, stiffness = 400f) 
                }, label = "leftEdge"
            ) { index -> tabWidth * index + (tabWidth / 2) - indicatorHalfWidth }

            val rightEdge by transition.animateDp(
                transitionSpec = {
                    if (targetState > initialState) spring(dampingRatio = 0.65f, stiffness = 400f) 
                    else spring(dampingRatio = 0.65f, stiffness = 150f) 
                }, label = "rightEdge"
            ) { index -> tabWidth * index + (tabWidth / 2) + indicatorHalfWidth }

            Box(
                modifier = Modifier
                    .offset(x = leftEdge, y = 16.dp)
                    .width(rightEdge - leftEdge)
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            )

            Row(modifier = Modifier.fillMaxSize()) {
                items.forEachIndexed { index, item ->
                    val selected = selectedIndex == index
                    val iconColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "")
                    val textColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, label = "")
                    val textWeight = if (selected) FontWeight.Bold else FontWeight.Medium

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 2.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onItemSelected(index) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(modifier = Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                                BadgedBox(
                                    badge = {
                                        if (index == 3 && unreadErrors > 0) {
                                            Badge(containerColor = if (tunnelRunning) MaterialTheme.colorScheme.primary else Color.Red) {
                                                Text("$unreadErrors", color = Color.White)
                                            }
                                        }
                                    }
                                ) {
                                    AnimatedContent(
                                        targetState = selected,
                                        transitionSpec = {
                                            fadeIn(tween(120, delayMillis = 30)) togetherWith
                                                fadeOut(tween(90))
                                        },
                                        label = "nav_icon_${item.label}"
                                    ) { isSelected ->
                                        Icon(
                                            if (isSelected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label,
                                            tint = iconColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.label,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                color = textColor,
                                fontWeight = textWeight,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingOverlay(onComplete: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when(page) { 0 -> Icons.Default.CloudUpload; 1 -> Icons.Default.VpnKey; else -> Icons.Default.Shield },
                    contentDescription = null, modifier = Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    text = when(page) { 0 -> "Установка сервера"; 1 -> "VK Хэши"; else -> "Готово!" },
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = when(page) {
                        0 -> "Перейдите во вкладку «Ноды» и установите VPN на ваш VPS сервер в 1 клик."
                        1 -> "Создайте звонок ВКонтакте, скопируйте код из ссылки и вставьте в настройки Производительности."
                        else -> "Добавьте сервер на Главной и нажмите большую кнопку для подключения!"
                    },
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    val color by animateColorAsState(if (pagerState.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "")
                    val width by animateDpAsState(if (pagerState.currentPage == i) 24.dp else 8.dp, label = "")
                    Box(modifier = Modifier.size(width, 8.dp).clip(CircleShape).background(color))
                }
            }
            Button(onClick = { if (pagerState.currentPage < 2) scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } else onComplete() }, shape = RoundedCornerShape(16.dp)) {
                Text(if (pagerState.currentPage < 2) "Далее" else "Начать", fontWeight = FontWeight.Bold)
            }
        }
    }
}
