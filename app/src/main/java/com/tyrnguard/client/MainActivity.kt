package com.tyrnguard.client

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tyrnguard.client.ui.LogsTab
import com.tyrnguard.client.ui.SettingsTab
import com.tyrnguard.client.ui.DeployTab
import com.tyrnguard.client.ui.ExceptionsTab
import com.tyrnguard.client.ui.InfoTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.absoluteValue

class MainActivity : ComponentActivity() {

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { checkAndRequestVpn() }
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { checkAndRequestBattery() }
    private var permissionFlowStarted = false
    private var expectedDisconnect = false
    private var connectedTime = 0L

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
        val prefs = getSharedPreferences("wdtt_prefs", Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("is_first_launch", true)

        handleIntents(intent, settingsStore)

        lifecycleScope.launch {
            TunnelManager.running.collect { isRunning ->
                if (isRunning) {
                    connectedTime = System.currentTimeMillis()
                    expectedDisconnect = false
                } else {
                    if (!expectedDisconnect && connectedTime > 0 && (System.currentTimeMillis() - connectedTime) > 5000) {
                        showConnectionDropNotification()
                    }
                    connectedTime = 0L
                }
            }
        }

        setContent {
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle("system")
            val dynamicColor by settingsStore.useDynamicColor.collectAsStateWithLifecycle(true)
            var showOnboarding by remember { mutableStateOf(isFirstLaunch) }

            WDTTTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
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
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntents(intent, SettingsStore(this))
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
        val intent = Intent(this, MainActivity::class.java).apply { action = "com.wdtt.client.ACTION_CONNECT" }
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
                .setIntent(Intent(this, MainActivity::class.java).apply { action = "com.wdtt.client.ACTION_CONNECT" })
                .build()
            val disconnect = ShortcutInfo.Builder(this, "disconnect_vpn")
                .setShortLabel("Отключить")
                .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel))
                .setIntent(Intent(this, MainActivity::class.java).apply { action = "com.wdtt.client.ACTION_DISCONNECT" })
                .build()
            sm.dynamicShortcuts = listOf(connect, disconnect)
        }
    }

    private fun handleIntents(intent: Intent, settingsStore: SettingsStore) {
        when (intent.action) {
            "com.wdtt.client.ACTION_CONNECT" -> {
                lifecycleScope.launch {
                    val peer = settingsStore.peer.first()
                    val hashes = settingsStore.vkHashes.first()
                    if (peer.isNotBlank() && hashes.isNotBlank()) {
                        val startIntent = Intent(applicationContext, TunnelService::class.java).apply {
                            action = "START"
                            putExtra("peer", "$peer:56000")
                            putExtra("vk_hashes", hashes)
                            putExtra("workers_per_hash", settingsStore.workersPerHash.first())
                            putExtra("port", settingsStore.listenPort.first())
                            putExtra("protocol", settingsStore.protocol.first())
                            putExtra("captcha_mode", settingsStore.captchaMode.first())
                        }
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(startIntent) else startService(startIntent)
                    }
                }
            }
            "com.wdtt.client.ACTION_DISCONNECT" -> {
                expectedDisconnect = true
                startService(Intent(this, TunnelService::class.java).apply { action = "STOP" })
            }
        }
        val data = intent.data ?: return
        if (data.scheme == "wdtt" && data.host == "config") {
            val base64Data = data.getQueryParameter("data") ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val json = JSONObject(String(Base64.decode(base64Data, Base64.URL_SAFE), Charsets.UTF_8))
                    settingsStore.save(
                        peer = json.optString("peer", "").trim(),
                        vkHashes = json.optString("hashes", "").trim(),
                        secondaryVkHash = "",
                        workersPerHash = json.optInt("workers", 24),
                        protocol = if (json.optBoolean("tcp", false)) "tcp" else "udp",
                        listenPort = 9000,
                        sni = ""
                    )
                    settingsStore.saveConnectionPassword(json.optString("password", "").trim())
                    val captchaMode = json.optString("captchaSolveMethod", "manual")
                    settingsStore.saveCaptchaMode(if (captchaMode == "auto") "rjs" else "wv")
                    settingsStore.saveCaptchaSolveMethod(captchaMode)
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Импорт успешен", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Ошибка импорта", Toast.LENGTH_SHORT).show() }
                }
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
            } catch (e: Exception) { checkAndRequestVpn() }
        } else checkAndRequestVpn()
    }

    private fun checkAndRequestVpn() {
        try { VpnService.prepare(this)?.let { vpnLauncher.launch(it) } } catch (e: Exception) { e.printStackTrace() }
    }
}

data class NavItem(val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector)

val navItems = listOf(
    NavItem("Главная", Icons.Filled.Home, Icons.Outlined.Home),
    NavItem("Деплой", Icons.Filled.Cloud, Icons.Outlined.Cloud),
    NavItem("Исключения", Icons.Filled.FilterList, Icons.Outlined.FilterList),
    NavItem("Логи", Icons.Filled.Terminal, Icons.Outlined.Terminal),
    NavItem("Настройки", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { navItems.size })
    val unreadErrors by TunnelManager.unreadErrorCount.collectAsStateWithLifecycle()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pagerState.currentPage) {
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
                    0 -> SettingsTab(snackbarHostState)
                    1 -> DeployTab(snackbarHostState)
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
            val transition = updateTransition(targetState = selectedIndex, label = "tab_transition")

            val leftEdge by transition.animateDp(
                transitionSpec = {
                    if (targetState > initialState) spring(dampingRatio = 0.65f, stiffness = 150f) 
                    else spring(dampingRatio = 0.65f, stiffness = 400f) 
                }, label = "leftEdge"
            ) { index -> tabWidth * index + (tabWidth / 2) - 32.dp }

            val rightEdge by transition.animateDp(
                transitionSpec = {
                    if (targetState > initialState) spring(dampingRatio = 0.65f, stiffness = 400f) 
                    else spring(dampingRatio = 0.65f, stiffness = 150f) 
                }, label = "rightEdge"
            ) { index -> tabWidth * index + (tabWidth / 2) + 32.dp }

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
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onItemSelected(index) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
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
                                    Icon(if (selected) item.selectedIcon else item.unselectedIcon, contentDescription = item.label, tint = iconColor, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(item.label, style = MaterialTheme.typography.labelSmall, color = textColor, fontWeight = textWeight, maxLines = 1)
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
                        0 -> "Перейдите во вкладку «Деплой» и установите VPN на ваш VPS сервер в 1 клик."
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