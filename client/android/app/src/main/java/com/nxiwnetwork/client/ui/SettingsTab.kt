package com.nxiwnetwork.client.ui

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nxiwnetwork.client.DEFAULT_NODE_PORT
import com.nxiwnetwork.client.CoreBackend
import com.nxiwnetwork.client.isValidNodeAddress
import com.nxiwnetwork.client.nodeEndpointHost
import com.nxiwnetwork.client.nodeEndpointPort
import com.nxiwnetwork.client.normalizeNodeAddressForStorage
import com.nxiwnetwork.client.normalizeNodeEndpoint
import com.nxiwnetwork.client.SettingsStore
import com.nxiwnetwork.client.TunnelManager
import com.nxiwnetwork.client.TunnelService
import com.nxiwnetwork.client.resolveCoreBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.math.roundToInt

data class NxiwNetworkServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val password: String
)

fun captchaModeForMethod(method: String): String = when (method) {
    "rjs_classic" -> "rjs"
    "rjs_slider" -> "rjs_slider"
    "auto" -> "rjs"
    else -> "wv"
}

val captchaMethodOptions = listOf(
    "manual" to "WebView",
    "rjs_classic" to "RJS",
    "rjs_slider" to "Slider"
)

enum class WidgetType(val title: String, val icon: ImageVector, val isWide: Boolean = false, val isUserWidget: Boolean = true) {
    NODE("Текущая нода", Icons.Default.Dns, isWide = true),
    CONTROL("Подключение", Icons.Default.PowerSettingsNew, isWide = true, isUserWidget = false),
    PING("Пинг", Icons.Default.NetworkPing),
    SESSION("Сессия", Icons.Default.Timer),
    WORKERS("Воркеры", Icons.Default.Hub),
    SPEED("Скорость", Icons.Default.Download),
    GRAPH("График сети", Icons.Default.QueryStats, isWide = true)
}

enum class SpeedMetricMode(val id: String, val label: String, val chipLabel: String) {
    TOTAL("total", "Отдача + скачка", "↑+↓"),
    UP("up", "Отдача", "↑"),
    DOWN("down", "Скачка", "↓")
}

fun parseSpeedMetricMode(raw: String): SpeedMetricMode {
    return SpeedMetricMode.entries.find { it.id == raw.lowercase() } ?: SpeedMetricMode.TOTAL
}

fun speedMetricValue(mode: SpeedMetricMode, uploadBytes: Long, downloadBytes: Long): Long {
    return when (mode) {
        SpeedMetricMode.TOTAL -> uploadBytes + downloadBytes
        SpeedMetricMode.UP -> uploadBytes
        SpeedMetricMode.DOWN -> downloadBytes
    }
}

fun parseDashboardWidgets(raw: String): List<WidgetType> {
    val parsed = if (raw.isBlank()) {
        emptyList()
    } else {
        raw.split(",")
            .mapNotNull { name -> WidgetType.entries.find { it.name == name.trim() } }
            .distinct()
    }
    val widgets = parsed.ifEmpty {
        SettingsStore.DEFAULT_DASHBOARD_WIDGETS.split(",")
            .mapNotNull { name -> WidgetType.entries.find { it.name == name } }
    }
    if (WidgetType.CONTROL in widgets) return widgets

    val insertIndex = widgets.indexOf(WidgetType.NODE).takeIf { it >= 0 }?.plus(1) ?: 0
    return widgets.toMutableList().apply {
        add(insertIndex.coerceIn(0, size), WidgetType.CONTROL)
    }
}

fun encodeDashboardWidgets(widgets: List<WidgetType>): String = widgets.distinct().joinToString(",") { it.name }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    var pendingVpnStartIntent by remember { mutableStateOf<Intent?>(null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val startIntent = pendingVpnStartIntent
        pendingVpnStartIntent = null
        if (startIntent != null && (result.resultCode == Activity.RESULT_OK || VpnService.prepare(context) == null)) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(startIntent) else context.startService(startIntent)
        }
    }

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val cooldownSeconds by TunnelManager.cooldownSeconds.collectAsStateWithLifecycle()
    val tunnelStartedAtElapsedMs by TunnelManager.tunnelStartedAtElapsedMs.collectAsStateWithLifecycle()
    val currentPing by TunnelManager.currentPingMs.collectAsStateWithLifecycle()
    val currentUploadSpeed by TunnelManager.currentUploadSpeedBytes.collectAsStateWithLifecycle()
    val currentDownloadSpeed by TunnelManager.currentDownloadSpeedBytes.collectAsStateWithLifecycle()
    val trafficGraphPoints by TunnelManager.trafficGraphPoints.collectAsStateWithLifecycle()
    val uploadTrafficGraphPoints by TunnelManager.uploadTrafficGraphPoints.collectAsStateWithLifecycle()
    val downloadTrafficGraphPoints by TunnelManager.downloadTrafficGraphPoints.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()

    val peer by settingsStore.peer.collectAsStateWithLifecycle("")
    val hashes by settingsStore.vkHashes.collectAsStateWithLifecycle("")
    val workers by settingsStore.workersPerHash.collectAsStateWithLifecycle(12)
    val wifiHighPerformance by settingsStore.wifiHighPerformance.collectAsStateWithLifecycle(true)
    val clientKeepaliveSeconds by settingsStore.clientKeepaliveSeconds.collectAsStateWithLifecycle(10)
    val port by settingsStore.listenPort.collectAsStateWithLifecycle(9000)
    val sni by settingsStore.sni.collectAsStateWithLifecycle("")
    val connPass by settingsStore.connectionPassword.collectAsStateWithLifecycle("")
    val protocol by settingsStore.protocol.collectAsStateWithLifecycle("udp")
    val captchaMethod by settingsStore.captchaSolveMethod.collectAsStateWithLifecycle("manual")
    val savedServersJson by settingsStore.savedServersJson.collectAsStateWithLifecycle("[]")
    val dashboardWidgetsRaw by settingsStore.dashboardWidgets.collectAsStateWithLifecycle(SettingsStore.DEFAULT_DASHBOARD_WIDGETS)
    val dashboardNodeWidgetMigrated by settingsStore.dashboardNodeWidgetMigrated.collectAsStateWithLifecycle(false)
    val speedMetricModeRaw by settingsStore.speedMetricMode.collectAsStateWithLifecycle(SettingsStore.DEFAULT_SPEED_METRIC_MODE)
    val graphSpeedMetricModeRaw by settingsStore.graphSpeedMetricMode.collectAsStateWithLifecycle(SettingsStore.DEFAULT_SPEED_METRIC_MODE)
    val speedMetricMode = parseSpeedMetricMode(speedMetricModeRaw)
    val graphSpeedMetricMode = parseSpeedMetricMode(graphSpeedMetricModeRaw)
    val displayedSpeed = speedMetricValue(speedMetricMode, currentUploadSpeed, currentDownloadSpeed)
    val displayedGraphSpeed = speedMetricValue(graphSpeedMetricMode, currentUploadSpeed, currentDownloadSpeed)
    val displayedGraphPoints = when (graphSpeedMetricMode) {
        SpeedMetricMode.TOTAL -> trafficGraphPoints
        SpeedMetricMode.UP -> uploadTrafficGraphPoints
        SpeedMetricMode.DOWN -> downloadTrafficGraphPoints
    }

    val serverList = remember { mutableStateListOf<NxiwNetworkServer>() }
    var activeWidgetList by remember { mutableStateOf(listOf<WidgetType>()) }
    var availableWidgetList by remember { mutableStateOf(listOf<WidgetType>()) }

    LaunchedEffect(dashboardWidgetsRaw, dashboardNodeWidgetMigrated) {
        var active = parseDashboardWidgets(dashboardWidgetsRaw)
        if (!dashboardNodeWidgetMigrated) {
            if (WidgetType.NODE !in active) {
                active = listOf(WidgetType.NODE) + active
                settingsStore.saveDashboardWidgets(encodeDashboardWidgets(active))
            }
            settingsStore.saveDashboardNodeWidgetMigrated(true)
        }
        activeWidgetList = active
        availableWidgetList = WidgetType.entries.filter { it.isUserWidget } - active.toSet()
    }

    LaunchedEffect(savedServersJson) {
        serverList.clear()
        try {
            val array = JSONArray(savedServersJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                serverList.add(NxiwNetworkServer(obj.optString("id", UUID.randomUUID().toString()), obj.optString("name"), obj.optString("ip").trim(), obj.optString("password").trim()))
            }
        } catch (_: Exception) {}
    }

    val activePeer = peer.trim()
    val activeServer by remember(activePeer) {
        derivedStateOf { serverList.find { normalizeNodeEndpoint(it.ip) == normalizeNodeEndpoint(activePeer) } }
    }

    var showDiagnosticDialog by remember { mutableStateOf(false) }
    var sessionTickerMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var isEditMode by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(tunnelRunning, tunnelStartedAtElapsedMs) {
        while (tunnelRunning && tunnelStartedAtElapsedMs != null) {
            sessionTickerMs = SystemClock.elapsedRealtime()
            delay(1000)
        }
        sessionTickerMs = SystemClock.elapsedRealtime()
    }

    val sessionSeconds = if (tunnelRunning) {
        ((sessionTickerMs - (tunnelStartedAtElapsedMs ?: sessionTickerMs)) / 1000L).coerceAtLeast(0L)
    } else {
        0L
    }
    val timerString = String.format("%02d:%02d:%02d", sessionSeconds / 3600, (sessionSeconds % 3600) / 60, sessionSeconds % 60)

    fun updateWidgetOrder(newList: List<WidgetType>) {
        activeWidgetList = newList
        availableWidgetList = WidgetType.entries.filter { it.isUserWidget } - newList.toSet()
        scope.launch { settingsStore.saveDashboardWidgets(encodeDashboardWidgets(newList)) }
    }

    fun startTunnelWithPermission(intent: Intent) {
        val permissionIntent = VpnService.prepare(context)
        if (permissionIntent != null) {
            pendingVpnStartIntent = intent
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    // Органическая iOS-тряска (сдвиги + ротация)
    val infiniteTransition = rememberInfiniteTransition(label = "jiggle")
    val jiggleRotation by infiniteTransition.animateFloat(initialValue = -1.5f, targetValue = 1.5f, animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse), label = "rotation")
    val jiggleTx by infiniteTransition.animateFloat(initialValue = -1.5f, targetValue = 1.5f, animationSpec = infiniteRepeatable(tween(130, easing = LinearEasing), RepeatMode.Reverse), label = "tx")
    val jiggleTy by infiniteTransition.animateFloat(initialValue = -1.5f, targetValue = 1.5f, animationSpec = infiniteRepeatable(tween(110, easing = LinearEasing), RepeatMode.Reverse), label = "ty")

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize(spring(stiffness = Spring.StiffnessLow)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Главная", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            IconButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); isEditMode = !isEditMode },
                modifier = Modifier.background(if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            ) { Icon(if (isEditMode) Icons.Default.Check else Icons.Default.Edit, null, tint = if (isEditMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        DashboardWidgetsSection(
            activeWidgetList = activeWidgetList,
            availableWidgetList = availableWidgetList,
            isEditMode = isEditMode,
            tunnelRunning = tunnelRunning,
            cooldownSeconds = cooldownSeconds,
            protocol = protocol,
            currentPing = currentPing,
            timerString = timerString,
            activeWorkers = activeWorkers,
            displayedSpeed = displayedSpeed,
            displayedGraphSpeed = displayedGraphSpeed,
            currentUploadSpeed = currentUploadSpeed,
            currentDownloadSpeed = currentDownloadSpeed,
            displayedGraphPoints = displayedGraphPoints,
            activeServerName = activeServer?.name ?: "Не выбран",
            jiggleRotation = jiggleRotation,
            jiggleTx = jiggleTx,
            jiggleTy = jiggleTy,
            haptic = haptic,
            onUpdateWidgetOrder = ::updateWidgetOrder,
            onDiagnosticsClick = { showDiagnosticDialog = true },
            onProtocolSelected = { selectedProtocol ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                scope.launch { settingsStore.save(peer, hashes, "", workers, selectedProtocol, port, sni) }
            },
            onPowerClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (tunnelRunning) {
                    context.startService(Intent(context, TunnelService::class.java).apply { action = "STOP" })
                } else {
                    if (peer.isBlank() || hashes.isBlank()) {
                        Toast.makeText(context, "Выберите ноду и укажите хеши!", Toast.LENGTH_SHORT).show()
                    } else {
                        val intent = Intent(context, TunnelService::class.java).apply {
                            action = "START"
                            putExtra("peer", normalizeNodeEndpoint(peer))
                            putExtra("vk_hashes", hashes)
                            putExtra("workers_per_hash", workers)
                            putExtra("port", port)
                            putExtra("sni", sni)
                            putExtra("connection_password", connPass.trim())
                            putExtra("protocol", protocol)
                            putExtra("captcha_mode", captchaModeForMethod(captchaMethod))
                            putExtra("wifi_high_performance", wifiHighPerformance)
                            putExtra("client_keepalive_seconds", clientKeepaliveSeconds)
                        }
                        startTunnelWithPermission(intent)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showDiagnosticDialog) DiagnosticDialog(context = context, peer = peer, hashes = hashes) { showDiagnosticDialog = false }
}

@Composable
private fun DashboardWidgetsSection(
    activeWidgetList: List<WidgetType>,
    availableWidgetList: List<WidgetType>,
    isEditMode: Boolean,
    tunnelRunning: Boolean,
    cooldownSeconds: Int,
    protocol: String,
    currentPing: Int,
    timerString: String,
    activeWorkers: Int,
    displayedSpeed: Long,
    displayedGraphSpeed: Long,
    currentUploadSpeed: Long,
    currentDownloadSpeed: Long,
    displayedGraphPoints: List<Float>,
    activeServerName: String,
    jiggleRotation: Float,
    jiggleTx: Float,
    jiggleTy: Float,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onUpdateWidgetOrder: (List<WidgetType>) -> Unit,
    onDiagnosticsClick: () -> Unit,
    onProtocolSelected: (String) -> Unit,
    onPowerClick: () -> Unit
) {
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    var previewWidgetList by remember(activeWidgetList) { mutableStateOf(activeWidgetList) }
    var draggingWidget by remember { mutableStateOf<WidgetType?>(null) }
    var draggingWidgetIndex by remember { mutableStateOf<Int?>(null) }
    var dragVisualPosition by remember { mutableStateOf(Offset.Zero) }
    var draggedWidgetSize by remember { mutableStateOf(IntSize.Zero) }
    var awaitingDropTarget by remember { mutableStateOf(false) }
    var dropAnimationTarget by remember { mutableStateOf<Offset?>(null) }
    var pendingDropWidgetList by remember { mutableStateOf<List<WidgetType>?>(null) }
    val isDropAnimating = dropAnimationTarget != null
    val dragAnimationSpec = if (isDropAnimating) {
        tween<Float>(durationMillis = 190, easing = FastOutSlowInEasing)
    } else {
        snap()
    }
    val dragTargetPosition = dropAnimationTarget ?: dragVisualPosition
    val animatedDragX by animateFloatAsState(
        targetValue = dragTargetPosition.x,
        animationSpec = dragAnimationSpec,
        label = "dragOverlayX"
    )
    val animatedDragY by animateFloatAsState(
        targetValue = dragTargetPosition.y,
        animationSpec = dragAnimationSpec,
        label = "dragOverlayY"
    )
    val dragOverlayScale by animateFloatAsState(
        targetValue = if (isDropAnimating) 1f else 1.06f,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "dragOverlayScale"
    )

    LaunchedEffect(activeWidgetList) {
        if (draggingWidget == null && !awaitingDropTarget && dropAnimationTarget == null) previewWidgetList = activeWidgetList
    }

    LaunchedEffect(awaitingDropTarget, pendingDropWidgetList, draggingWidgetIndex) {
        if (awaitingDropTarget && pendingDropWidgetList != null) {
            withFrameNanos { }
            dropAnimationTarget = draggingWidgetIndex?.let { index ->
                gridState.layoutInfo.visibleItemsInfo
                    .find { it.index == index }
                    ?.offset
                    ?.let { Offset(it.x.toFloat(), it.y.toFloat()) }
            } ?: dragVisualPosition
            awaitingDropTarget = false
        }
    }

    LaunchedEffect(dropAnimationTarget, pendingDropWidgetList) {
        val finalList = pendingDropWidgetList
        if (dropAnimationTarget != null && finalList != null) {
            delay(200)
            if (finalList != activeWidgetList) {
                onUpdateWidgetOrder(finalList)
            }
            draggingWidget = null
            draggingWidgetIndex = null
            dragVisualPosition = Offset.Zero
            draggedWidgetSize = IntSize.Zero
            awaitingDropTarget = false
            dropAnimationTarget = null
            pendingDropWidgetList = null
            previewWidgetList = finalList
        }
    }

    LaunchedEffect(isEditMode) {
        if (!isEditMode) {
            draggingWidget = null
            draggingWidgetIndex = null
            dragVisualPosition = Offset.Zero
            draggedWidgetSize = IntSize.Zero
            awaitingDropTarget = false
            dropAnimationTarget = null
            pendingDropWidgetList = null
            previewWidgetList = activeWidgetList
        }
    }

    @Composable
    fun DashboardWidgetContent(widget: WidgetType, modifier: Modifier = Modifier) {
        when (widget) {
            WidgetType.NODE -> {
                NodeDashboardCard(
                    activeServerName = activeServerName,
                    onDiagnosticsClick = onDiagnosticsClick,
                    modifier = modifier.height(104.dp)
                )
            }
            WidgetType.CONTROL -> {
                ConnectControlWidget(
                    protocol = protocol,
                    tunnelRunning = tunnelRunning,
                    cooldownSeconds = cooldownSeconds,
                    onProtocolSelected = onProtocolSelected,
                    onPowerClick = onPowerClick,
                    modifier = modifier.height(334.dp)
                )
            }
            WidgetType.GRAPH -> {
                SpeedGraphCard(
                    isRunning = tunnelRunning,
                    currentSpeedBytes = displayedGraphSpeed,
                    uploadSpeedBytes = currentUploadSpeed,
                    downloadSpeedBytes = currentDownloadSpeed,
                    points = displayedGraphPoints,
                    modifier = modifier.height(160.dp)
                )
            }
            else -> {
                DashboardCard(title = widget.title, icon = widget.icon, modifier = modifier.height(130.dp)) {
                    val value = when (widget) {
                        WidgetType.PING -> if (tunnelRunning && currentPing > 0) "${currentPing} ms" else "--"
                        WidgetType.SESSION -> if (tunnelRunning) timerString else "00:00:00"
                        WidgetType.WORKERS -> "$activeWorkers"
                        WidgetType.SPEED -> {
                            val speedKb = displayedSpeed / 1024f
                            if (tunnelRunning) {
                                if (speedKb > 1024) String.format("%.1f MB/s", speedKb / 1024f) else String.format("%.0f KB/s", speedKb)
                            } else {
                                "0 KB/s"
                            }
                        }
                    }
                    AnimatedDashboardValue(value)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 1000.dp)
            .pointerInput(isEditMode, activeWidgetList) {
                if (!isEditMode) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = dragStart@{ offset ->
                        if (dropAnimationTarget != null) return@dragStart
                        val item = gridState.layoutInfo.visibleItemsInfo.find {
                            offset.x >= it.offset.x && offset.x <= it.offset.x + it.size.width &&
                                offset.y >= it.offset.y && offset.y <= it.offset.y + it.size.height
                        }
                        val draggableIndex = item?.index?.takeIf { index ->
                            previewWidgetList.getOrNull(index)?.isUserWidget == true
                        }
                        draggingWidget = draggableIndex?.let { previewWidgetList[it] }
                        draggingWidgetIndex = draggableIndex
                        dragVisualPosition = item?.offset?.let { Offset(it.x.toFloat(), it.y.toFloat()) } ?: Offset.Zero
                        draggedWidgetSize = item?.size ?: IntSize.Zero
                        awaitingDropTarget = false
                        dropAnimationTarget = null
                        pendingDropWidgetList = null
                        if (draggableIndex != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, dragAmount ->
                        if (draggingWidgetIndex != null) {
                            change.consume()
                            dragVisualPosition += dragAmount
                            val pointer = change.position
                            val hoveredItem = gridState.layoutInfo.visibleItemsInfo.find {
                                pointer.x >= it.offset.x && pointer.x <= it.offset.x + it.size.width &&
                                    pointer.y >= it.offset.y && pointer.y <= it.offset.y + it.size.height
                            }
                            val insertionIndex = hoveredItem?.let { item ->
                                val afterItemCenter = pointer.y > item.offset.y + item.size.height / 2f
                                (item.index + if (afterItemCenter) 1 else 0).coerceIn(0, previewWidgetList.size)
                            }
                            val currentDraggingWidget = draggingWidget
                            val from = currentDraggingWidget?.let { previewWidgetList.indexOf(it) } ?: -1
                            if (
                                currentDraggingWidget != null &&
                                insertionIndex != null &&
                                from in previewWidgetList.indices
                            ) {
                                val to = (if (insertionIndex > from) insertionIndex - 1 else insertionIndex).coerceIn(0, previewWidgetList.lastIndex)
                                if (to != from) {
                                    val list = previewWidgetList.toMutableList()
                                    val moved = list.removeAt(from)
                                    list.add(to, moved)
                                    previewWidgetList = list
                                    draggingWidgetIndex = to
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        if (draggingWidgetIndex != null) {
                            pendingDropWidgetList = previewWidgetList
                            awaitingDropTarget = true
                        } else {
                            if (previewWidgetList != activeWidgetList) {
                                onUpdateWidgetOrder(previewWidgetList)
                            }
                            draggingWidget = null
                            draggingWidgetIndex = null
                            dragVisualPosition = Offset.Zero
                            draggedWidgetSize = IntSize.Zero
                            awaitingDropTarget = false
                        }
                    },
                    onDragCancel = {
                        draggingWidget = null
                        draggingWidgetIndex = null
                        dragVisualPosition = Offset.Zero
                        draggedWidgetSize = IntSize.Zero
                        awaitingDropTarget = false
                        dropAnimationTarget = null
                        pendingDropWidgetList = null
                        previewWidgetList = activeWidgetList
                    }
                )
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        items(previewWidgetList, key = { it.name }, span = { if (it.isWide) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { widget ->
            val index = previewWidgetList.indexOf(widget)
            val isDragging = draggingWidgetIndex == index
            val isDraggingAny = draggingWidget != null
            val canEditWidget = widget.isUserWidget
            val rotate = if (isEditMode && canEditWidget && !isDragging && !isDraggingAny) (if (index % 2 == 0) jiggleRotation else -jiggleRotation) else 0f
            val tx = if (isEditMode && canEditWidget && !isDragging && !isDraggingAny) (if (index % 3 == 0) jiggleTx else -jiggleTx) else 0f
            val ty = if (isEditMode && canEditWidget && !isDragging && !isDraggingAny) (if (index % 2 != 0) jiggleTy else -jiggleTy) else 0f

            Box(
                modifier = Modifier
                    .animateItem(placementSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing))
                    .graphicsLayer {
                        alpha = if (isDragging) 0f else 1f
                        rotationZ = rotate
                        translationX = tx.dp.toPx()
                        translationY = ty.dp.toPx()
                    }
            ) {
                DashboardWidgetContent(widget)

                androidx.compose.animation.AnimatedVisibility(
                    visible = isEditMode && canEditWidget,
                    enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow)),
                    exit = scaleOut(spring(stiffness = Spring.StiffnessMediumLow)),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onUpdateWidgetOrder(activeWidgetList - widget)
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Remove, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

        draggingWidget?.let { widget ->
            if (draggedWidgetSize != IntSize.Zero) {
                val overlayWidth = with(density) { draggedWidgetSize.width.toDp() }
                val overlayHeight = with(density) { draggedWidgetSize.height.toDp() }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                animatedDragX.roundToInt(),
                                animatedDragY.roundToInt()
                            )
                        }
                        .width(overlayWidth)
                        .height(overlayHeight)
                        .zIndex(50f)
                        .graphicsLayer {
                            scaleX = dragOverlayScale
                            scaleY = dragOverlayScale
                            shadowElevation = 30f
                        }
                ) {
                    DashboardWidgetContent(widget)
                }
            }
        }
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = isEditMode && availableWidgetList.isNotEmpty(),
        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
            Text("Доступные виджеты", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false,
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(availableWidgetList, key = { it.name }, span = { if (it.isWide) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { widget ->
                    val index = availableWidgetList.indexOf(widget)
                    val rotate = if (draggingWidget == null) (if (index % 2 == 0) -jiggleRotation else jiggleRotation) * 0.7f else 0f

                    Box(modifier = Modifier.animateItem().graphicsLayer { rotationZ = rotate; alpha = 0.8f }) {
                        Surface(modifier = Modifier.fillMaxWidth().height(if (widget.isWide) 80.dp else 130.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(widget.icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text(widget.title, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onUpdateWidgetOrder(activeWidgetList + widget)
                            },
                            shape = CircleShape,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ПРЕМИУМ АНИМАЦИЯ: Мягкое дыхание вместо старых дерганых колец
@Composable
fun PremiumRadarWaves(isConnected: Boolean) {
    val t = rememberInfiniteTransition(label = "")
    val scale by t.animateFloat(
        initialValue = 1.0f, 
        targetValue = if (isConnected) 1.35f else 1.8f, 
        animationSpec = infiniteRepeatable(tween(if (isConnected) 2000 else 1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = ""
    )
    val alpha by t.animateFloat(
        initialValue = if (isConnected) 0.6f else 0.4f, 
        targetValue = 0f, 
        animationSpec = infiniteRepeatable(tween(if (isConnected) 2000 else 1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = ""
    )
    
    Box(modifier = Modifier.size(150.dp).scale(scale).alpha(alpha).background(MaterialTheme.colorScheme.primary, CircleShape))
    if (!isConnected) { // Дополнительное кольцо только при поиске
        val scale2 by t.animateFloat(initialValue = 0.8f, targetValue = 2.2f, animationSpec = infiniteRepeatable(tween(1500, 500, FastOutSlowInEasing)), label = "")
        val alpha2 by t.animateFloat(initialValue = 0.3f, targetValue = 0f, animationSpec = infiniteRepeatable(tween(1500, 500, FastOutSlowInEasing)), label = "")
        Box(modifier = Modifier.size(150.dp).scale(scale2).alpha(alpha2).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}

@Composable
fun DiagnosticDialog(context: Context, peer: String, hashes: String, onDismiss: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(listOf<Boolean?>(null, null, null, null)) }
    
    LaunchedEffect(Unit) {
        if (peer.isBlank()) {
            results = listOf(false, false, false, false)
            step = 4
            return@LaunchedEffect
        }
        
        withContext(Dispatchers.IO) {
            val ip = nodeEndpointHost(peer)
            val nodePort = nodeEndpointPort(peer, DEFAULT_NODE_PORT)
            
            // 1. Доступность интернета. При активном туннеле учитываем VPN network и живые воркеры:
            // само приложение исключено из VPN, поэтому прямой сокет из UI-процесса может дать ложный минус.
            val internetOk = checkInternetAccess(context, preferTunnel = TunnelManager.running.value)
            results = results.toMutableList().apply { set(0, internetOk) }; step = 1

            // 2. Доступность ноды (Ping или TCP fallback)
            val serverOk = if (ip.isBlank()) {
                false
            } else try {
                val process = Runtime.getRuntime().exec("ping -c 1 -W 2 $ip")
                if (process.waitFor() == 0) {
                    true
                } else {
                    try {
                        Socket().use { it.connect(InetSocketAddress(ip, nodePort), 1500) }
                        true
                    } catch (_: Exception) {
                        Socket().use { it.connect(InetSocketAddress(ip, 22), 1500) }
                        true
                    }
                }
            } catch (e: Exception) { false }
            results = results.toMutableList().apply { set(1, serverOk) }; step = 2

            // 3. Формат Хэшей
            val hashValid = hashes.isNotBlank() && hashes.split(",").any { it.trim().length > 20 }
            results = results.toMutableList().apply { set(2, hashValid) }; step = 3

            // 4. Готовность выбранного ядра или Go fallback
            val requestedBackend = CoreBackend.fromId(SettingsStore(context).coreBackend.first())
            val coreOk = resolveCoreBackend(context.applicationInfo.nativeLibraryDir, requestedBackend).binaryFile.exists()
            delay(500)
            results = results.toMutableList().apply { set(3, coreOk) }; step = 4
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth().animateContentSize()) {
                Text("Диагностика", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                val steps = listOf("Доступ к интернету", "Доступность ноды", "Формат VK Хэшей", "Ядро туннеля (Core)")
                steps.forEachIndexed { i, title ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(targetState = results[i], label = "") { res ->
                            when (res) {
                                null -> if (step == i) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Circle, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                                true -> Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(24.dp), tint = Color(0xFF4CAF50))
                                false -> Icon(Icons.Default.Cancel, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (step == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(20.dp), enabled = step == 4) { Text("Закрыть", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private fun checkInternetAccess(context: Context, preferTunnel: Boolean): Boolean {
    fun canConnect(socketFactory: javax.net.SocketFactory? = null, timeoutMs: Int): Boolean {
        val targets = listOf(
            InetSocketAddress("1.1.1.1", 443),
            InetSocketAddress("1.0.0.1", 443),
            InetSocketAddress("8.8.8.8", 443)
        )
        return targets.any { target ->
            try {
                val socket = socketFactory?.createSocket() ?: Socket()
                socket.use { it.connect(target, timeoutMs) }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    if (preferTunnel) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnNetworks = connectivityManager.allNetworks.mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                network to capabilities
            } else {
                null
            }
        }

        if (vpnNetworks.isEmpty()) {
            return false
        }

        if (vpnNetworks.any { (_, capabilities) -> capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }) {
            return true
        }

        if (vpnNetworks.any { (network, _) -> canConnect(network.socketFactory, timeoutMs = 2200) }) {
            return true
        }

        return TunnelManager.activeWorkers.value > 0
    }

    return canConnect(timeoutMs = 1500)
}

@Composable
fun SpeedGraphCard(
    isRunning: Boolean,
    currentSpeedBytes: Long,
    uploadSpeedBytes: Long,
    downloadSpeedBytes: Long,
    points: List<Float>,
    modifier: Modifier = Modifier
) {
    val safePoints = if (points.size >= 2) points else List(30) { 0f }
    val maxPoint = (safePoints.maxOrNull() ?: 1f).coerceAtLeast(1024 * 50f)
    val trafficLabel = "↑ ${formatGraphSpeed(if (isRunning) uploadSpeedBytes else 0L)}   ↓ ${formatGraphSpeed(if (isRunning) downloadSpeedBytes else 0L)}"

    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QueryStats, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Трафик сети", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val dotColor by animateColorAsState(if (isRunning && currentSpeedBytes > 1024) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, label = "")
                Box(modifier = Modifier.widthIn(min = 132.dp), contentAlignment = Alignment.CenterEnd) {
                    AnimatedContent(
                        targetState = trafficLabel,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                        label = "trafficSpeedLabel"
                    ) { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.size(10.dp).background(dotColor, CircleShape))
            }
            Spacer(Modifier.height(16.dp))
            val lineColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path()
                val stepX = size.width / (safePoints.size - 1)
                safePoints.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = size.height - (v / maxPoint * size.height)
                    if (i == 0) path.moveTo(x, y) else {
                        val px = (i - 1) * stepX
                        val py = size.height - (safePoints[i - 1] / maxPoint * size.height)
                        path.cubicTo(px + stepX / 2f, py, px + stepX / 2f, y, x, y)
                    }
                }
                drawPath(path = path, color = lineColor, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val fillPath = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(path = fillPath, brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.4f), Color.Transparent), 0f, size.height))
            }
        }
    }
}

private fun formatGraphSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0L) return "0 KB/s"
    val kb = bytesPerSecond / 1024f
    return if (kb >= 1024f) {
        String.format("%.1f MB/s", kb / 1024f)
    } else {
        String.format("%.0f KB/s", kb)
    }
}

@Composable
fun ConnectControlWidget(
    protocol: String,
    tunnelRunning: Boolean,
    cooldownSeconds: Int,
    onProtocolSelected: (String) -> Unit,
    onPowerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionStatusText = when {
        tunnelRunning -> "Подключено"
        cooldownSeconds > 4 -> "Подключение..."
        cooldownSeconds > 2 -> "Проверка конфигурации..."
        cooldownSeconds > 0 -> "Установка туннеля..."
        else -> "Нажмите для старта"
    }
    val mainBtnInteractionSource = remember { MutableInteractionSource() }
    val isMainBtnPressed by mainBtnInteractionSource.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = when {
            isMainBtnPressed -> 0.88f
            tunnelRunning -> 1.05f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "mainBtnScale"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = protocol == "udp",
                onClick = { onProtocolSelected("udp") },
                label = { Text("UDP", fontWeight = FontWeight.Bold) },
                enabled = !tunnelRunning
            )
            Spacer(modifier = Modifier.width(12.dp))
            FilterChip(
                selected = protocol == "tcp",
                onClick = { onProtocolSelected("tcp") },
                label = { Text("TCP", fontWeight = FontWeight.Bold) },
                enabled = !tunnelRunning
            )
        }

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
            if (tunnelRunning || cooldownSeconds > 0) PremiumRadarWaves(tunnelRunning)

            val circleColor by animateColorAsState(
                targetValue = if (tunnelRunning) MaterialTheme.colorScheme.primary else if (cooldownSeconds > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant,
                animationSpec = tween(500, easing = LinearOutSlowInEasing),
                label = ""
            )
            val iconColor by animateColorAsState(
                targetValue = if (tunnelRunning || cooldownSeconds > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = ""
            )

            Surface(
                modifier = Modifier
                    .size(150.dp)
                    .scale(buttonScale)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = mainBtnInteractionSource,
                        indication = null,
                        enabled = cooldownSeconds == 0 || tunnelRunning
                    ) {
                        onPowerClick()
                    },
                shape = CircleShape,
                color = circleColor,
                shadowElevation = if (tunnelRunning) 24.dp else 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (cooldownSeconds > 0 && !tunnelRunning) {
                        CircularProgressIndicator(color = iconColor, modifier = Modifier.size(70.dp), strokeWidth = 6.dp, strokeCap = StrokeCap.Round)
                    } else {
                        Icon(if (tunnelRunning) Icons.Default.Shield else Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(68.dp), tint = iconColor)
                    }
                }
            }
        }

        AnimatedContent(
            targetState = connectionStatusText,
            transitionSpec = { slideInVertically { it / 2 } + fadeIn(tween(300)) togetherWith slideOutVertically { -it / 2 } + fadeOut(tween(300)) },
            label = "statusText"
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = if (tunnelRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
fun NodeDashboardCard(activeServerName: String, onDiagnosticsClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(52.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Текущая нода", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    activeServerName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDiagnosticsClick) {
                Icon(Icons.Default.HealthAndSafety, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun DashboardCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun AnimatedDashboardValue(value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        value.forEachIndexed { index, char ->
            key(index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        if (targetState.isDigit() && initialState.isDigit()) {
                            slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(160)) togetherWith
                                slideOutVertically(spring(stiffness = Spring.StiffnessMediumLow)) { -it } + fadeOut(tween(160))
                        } else {
                            fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                        }
                    },
                    label = "dashboard_value_char_$index"
                ) { animatedChar ->
                    Text(
                        animatedChar.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }
}

@Composable
fun AddEditServerDialog(server: NxiwNetworkServer, onDismiss: () -> Unit, onSave: (NxiwNetworkServer) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(server.name) }
    var ip by remember { mutableStateOf(server.ip) }
    var pass by remember { mutableStateOf(server.password) }
    var passVisible by remember(server.id) { mutableStateOf(false) }
    val isNew = server.name.isBlank()
    val addressValid = ip.isBlank() || isValidNodeAddress(ip)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isNew) "Новая нода" else "Настройки сервера", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (!isNew) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Имя (напр. Германия)", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.filter { c -> !c.isWhitespace() } },
                    label = { Text("IP адрес", fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    isError = ip.isNotBlank() && !addressValid,
                    supportingText = if (ip.isNotBlank() && !addressValid) {
                        { Text("Проверь адрес ноды") }
                    } else {
                        null
                    }
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it.filter { c -> !c.isWhitespace() } },
                    label = { Text("Пароль от туннеля", fontSize = 14.sp) },
                    singleLine = true,
                    visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passVisible = !passVisible }) {
                            NodePasswordVisibilityIcon(hidden = !passVisible)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                    shape = RoundedCornerShape(16.dp)
                )
                Button(
                    onClick = { onSave(server.copy(name = name, ip = normalizeNodeAddressForStorage(ip), password = pass)) },
                    enabled = name.isNotBlank() && ip.isNotBlank() && addressValid,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(20.dp)
                ) { Text("Сохранить", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun NodePasswordVisibilityIcon(hidden: Boolean) {
    val slashProgress by animateFloatAsState(
        targetValue = if (hidden) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "nodePasswordSlashProgress"
    )
    val eyeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val slashColor = Color.Gray

    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = if (hidden) "Показать пароль" else "Скрыть пароль",
            tint = eyeColor
        )
        Canvas(modifier = Modifier.matchParentSize()) {
            if (slashProgress > 0.01f) {
                val start = Offset(size.width * 0.18f, size.height * 0.18f)
                val end = Offset(size.width * 0.82f, size.height * 0.82f)
                val current = Offset(
                    x = start.x + (end.x - start.x) * slashProgress,
                    y = start.y + (end.y - start.y) * slashProgress
                )
                drawLine(
                    color = slashColor.copy(alpha = slashProgress),
                    start = start,
                    end = current,
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
