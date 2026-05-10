package com.nxiwnetwork.client.ui

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.net.VpnService
import android.os.Build
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nxiwnetwork.client.DEFAULT_NODE_PORT
import com.nxiwnetwork.client.isValidNodeAddress
import com.nxiwnetwork.client.nodeEndpointHost
import com.nxiwnetwork.client.nodeEndpointPort
import com.nxiwnetwork.client.normalizeNodeAddressForStorage
import com.nxiwnetwork.client.normalizeNodeEndpoint
import com.nxiwnetwork.client.SettingsStore
import com.nxiwnetwork.client.TunnelManager
import com.nxiwnetwork.client.TunnelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.UUID

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

enum class WidgetType(val title: String, val icon: ImageVector, val isWide: Boolean = false) {
    PING("Пинг", Icons.Default.NetworkPing),
    SESSION("Сессия", Icons.Default.Timer),
    WORKERS("Воркеры", Icons.Default.Hub),
    SPEED("Скорость", Icons.Default.Download),
    GRAPH("График сети", Icons.Default.QueryStats, isWide = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val prefs = context.getSharedPreferences("nxiwnetwork_widgets", Context.MODE_PRIVATE)
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
    val currentPing by TunnelManager.currentPingMs.collectAsStateWithLifecycle()
    val currentSpeed by TunnelManager.currentSpeedBytes.collectAsStateWithLifecycle()
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

    val serverList = remember { mutableStateListOf<NxiwNetworkServer>() }
    var activeWidgetList by remember { mutableStateOf(listOf<WidgetType>()) }
    var availableWidgetList by remember { mutableStateOf(listOf<WidgetType>()) }

    LaunchedEffect(Unit) {
        val savedOrder = prefs.getString("order", null)
        if (savedOrder != null) {
            try {
                val active = savedOrder.split(",").mapNotNull { name -> WidgetType.entries.find { it.name == name } }
                activeWidgetList = active
                availableWidgetList = WidgetType.entries - active.toSet()
            } catch (e: Exception) {
                activeWidgetList = WidgetType.entries
                availableWidgetList = emptyList()
            }
        } else {
            activeWidgetList = WidgetType.entries
            availableWidgetList = emptyList()
        }
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
    var sessionSeconds by rememberSaveable { mutableIntStateOf(0) }
    var isEditMode by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(tunnelRunning) {
        if (tunnelRunning) { while (true) { delay(1000); sessionSeconds++ } } else sessionSeconds = 0
    }

    val timerString = String.format("%02d:%02d:%02d", sessionSeconds / 3600, (sessionSeconds % 3600) / 60, sessionSeconds % 60)

    fun updateWidgetOrder(newList: List<WidgetType>) {
        activeWidgetList = newList
        availableWidgetList = WidgetType.entries - newList.toSet()
        prefs.edit().putString("order", newList.joinToString(",") { it.name }).apply()
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

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { if (isEditMode) { rotationZ = jiggleRotation * 0.5f; translationX = jiggleTx; translationY = jiggleTy } },
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, tonalElevation = 2.dp
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(52.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.onPrimary) }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Текущая нода", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(activeServer?.name ?: "Не выбран", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                }
                if (isEditMode) {
                    Icon(Icons.Default.DragHandle, null, tint = MaterialTheme.colorScheme.outline)
                } else {
                    IconButton(onClick = { showDiagnosticDialog = true }) { Icon(Icons.Default.HealthAndSafety, null, tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = protocol == "udp", onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); scope.launch { settingsStore.save(peer, hashes, "", workers, "udp", port, sni) } }, label = { Text("UDP", fontWeight = FontWeight.Bold) }, enabled = !tunnelRunning)
            Spacer(modifier = Modifier.width(12.dp))
            FilterChip(selected = protocol == "tcp", onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); scope.launch { settingsStore.save(peer, hashes, "", workers, "tcp", port, sni) } }, label = { Text("TCP", fontWeight = FontWeight.Bold) }, enabled = !tunnelRunning)
        }

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
            targetValue = when { isMainBtnPressed -> 0.88f; tunnelRunning -> 1.05f; else -> 1f },
            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow), label = "mainBtnScale"
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
            if (tunnelRunning || cooldownSeconds > 0) PremiumRadarWaves(tunnelRunning)
            
            val circleColor by animateColorAsState(targetValue = if (tunnelRunning) MaterialTheme.colorScheme.primary else if (cooldownSeconds > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant, animationSpec = tween(500, easing = LinearOutSlowInEasing), label = "")
            val iconColor by animateColorAsState(targetValue = if (tunnelRunning || cooldownSeconds > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "")
            
            Surface(
                modifier = Modifier
                    .size(150.dp)
                    .scale(buttonScale)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = mainBtnInteractionSource, indication = null,
                        enabled = cooldownSeconds == 0 || tunnelRunning
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (tunnelRunning) {
                            context.startService(Intent(context, TunnelService::class.java).apply { action = "STOP" })
                        } else {
                            if (peer.isBlank() || hashes.isBlank()) { Toast.makeText(context, "Выберите ноду и укажите хеши!", Toast.LENGTH_SHORT).show(); return@clickable }
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
                    },
                shape = CircleShape, color = circleColor, shadowElevation = if (tunnelRunning) 24.dp else 8.dp
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
            Text(text, style = MaterialTheme.typography.titleMedium, color = if (tunnelRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp))
        }

        // Логика Drag-and-Drop (С zIndex для предотвращения проваливания!)
        val gridState = rememberLazyGridState()
        var draggingWidgetIndex by remember { mutableStateOf<Int?>(null) }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().heightIn(max = 1000.dp).pointerInput(isEditMode, activeWidgetList) {
                if (!isEditMode) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val item = gridState.layoutInfo.visibleItemsInfo.find {
                            offset.x >= it.offset.x && offset.x <= it.offset.x + it.size.width &&
                            offset.y >= it.offset.y && offset.y <= it.offset.y + it.size.height
                        }
                        draggingWidgetIndex = item?.index
                    },
                    onDrag = { change, _ ->
                        val pointer = change.position
                        val hoveredItem = gridState.layoutInfo.visibleItemsInfo.find {
                            pointer.x >= it.offset.x && pointer.x <= it.offset.x + it.size.width &&
                            pointer.y >= it.offset.y && pointer.y <= it.offset.y + it.size.height
                        }
                        if (hoveredItem != null && draggingWidgetIndex != null && hoveredItem.index != draggingWidgetIndex) {
                            val from = draggingWidgetIndex!!
                            val to = hoveredItem.index
                            if (from in activeWidgetList.indices && to in activeWidgetList.indices) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val list = activeWidgetList.toMutableList()
                                val temp = list[from]
                                list[from] = list[to]
                                list[to] = temp
                                updateWidgetOrder(list)
                                draggingWidgetIndex = to
                            }
                        }
                    },
                    onDragEnd = { draggingWidgetIndex = null },
                    onDragCancel = { draggingWidgetIndex = null }
                )
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false,
            contentPadding = PaddingValues(bottom = 12.dp) // Предотвращает обрезание теней
        ) {
            items(activeWidgetList, key = { it.name }, span = { if (it.isWide) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { widget ->
                val index = activeWidgetList.indexOf(widget)
                val isDragging = draggingWidgetIndex == index
                val rotate = if (isEditMode && !isDragging) (if (index % 2 == 0) jiggleRotation else -jiggleRotation) else 0f
                val tx = if (isEditMode && !isDragging) (if (index % 3 == 0) jiggleTx else -jiggleTx) else 0f
                val ty = if (isEditMode && !isDragging) (if (index % 2 != 0) jiggleTy else -jiggleTy) else 0f
                
                Box(
                    modifier = Modifier
                        .animateItem()
                        .zIndex(if (isDragging) 10f else 0f) // ВАЖНО: тянущийся элемент всегда сверху
                        .graphicsLayer { 
                            rotationZ = rotate
                            translationX = tx.dp.toPx()
                            translationY = ty.dp.toPx()
                            scaleX = if (isDragging) 1.08f else 1f
                            scaleY = if (isDragging) 1.08f else 1f
                            shadowElevation = if (isDragging) 30f else 0f 
                        }
                ) {
                    if (widget == WidgetType.GRAPH) {
                        SpeedGraphCard(isRunning = tunnelRunning, currentSpeedBytes = currentSpeed, modifier = Modifier.height(160.dp))
                    } else {
                        DashboardCard(title = widget.title, icon = widget.icon, modifier = Modifier.height(130.dp)) {
                            AnimatedContent(
                                targetState = when (widget) {
                                    WidgetType.PING -> if (tunnelRunning && currentPing > 0) "${currentPing}ms" else "--"
                                    WidgetType.SESSION -> if (tunnelRunning) timerString else "00:00"
                                    WidgetType.WORKERS -> "$activeWorkers"
                                    WidgetType.SPEED -> {
                                        val speedKb = currentSpeed / 1024f
                                        if (tunnelRunning) if (speedKb > 1024) String.format("%.1f MB/s", speedKb / 1024f) else String.format("%.0f KB/s", speedKb) else "0 KB/s"
                                    }
                                    else -> ""
                                },
                                transitionSpec = {
                                    if (targetState != "--" && targetState != "0 KB/s" && targetState != "00:00") {
                                        slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(200)) togetherWith 
                                        slideOutVertically(spring(stiffness = Spring.StiffnessMediumLow)) { -it } + fadeOut(tween(200))
                                    } else {
                                        fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                                    }
                                }, label = ""
                            ) { value -> 
                                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                            }
                        }
                    }
                    
                    // Кнопки больше не обрезаются, так как находятся ВНУТРИ карточки (через padding)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isEditMode,
                        enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow)), exit = scaleOut(spring(stiffness = Spring.StiffnessMediumLow)),
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    ) {
                        Surface(
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); updateWidgetOrder(activeWidgetList - widget) },
                            shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Remove, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(visible = isEditMode && availableWidgetList.isNotEmpty(), enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text("Доступные виджеты", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false,
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(availableWidgetList, key = { it.name }, span = { if (it.isWide) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { widget ->
                        val index = availableWidgetList.indexOf(widget)
                        val rotate = (if (index % 2 == 0) -jiggleRotation else jiggleRotation) * 0.7f
                        
                        Box(modifier = Modifier.animateItem().graphicsLayer { rotationZ = rotate; alpha = 0.8f }) {
                            Surface(modifier = Modifier.fillMaxWidth().height(if(widget.isWide) 80.dp else 130.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(widget.icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    Text(widget.title, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            // Кнопка добавления внутри карточки
                            Surface(
                                onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); updateWidgetOrder(activeWidgetList + widget) },
                                shape = CircleShape, color = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showDiagnosticDialog) DiagnosticDialog(context = context, peer = peer, hashes = hashes) { showDiagnosticDialog = false }
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
            
            // 1. Доступность интернета (DNS Google)
            val internetOk = try { Socket().use { it.connect(InetSocketAddress("8.8.8.8", 53), 1500) }; true } catch (e: Exception) { false }
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

            // 4. Готовность (наличие бинарника)
            val coreOk = File(context.applicationInfo.nativeLibraryDir + "/libclient.so").exists()
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

@Composable
fun SpeedGraphCard(isRunning: Boolean, currentSpeedBytes: Long, modifier: Modifier = Modifier) {
    val points = remember { mutableStateListOf<Float>().apply { repeat(30) { add(0f) } } }
    LaunchedEffect(currentSpeedBytes, isRunning) { points.removeAt(0); points.add(if (isRunning) currentSpeedBytes.toFloat() else 0f) }
    val maxPoint by remember(points) { derivedStateOf { (points.maxOrNull() ?: 1f).coerceAtLeast(1024 * 50f) } }

    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QueryStats, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Трафик сети", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val dotColor by animateColorAsState(if (isRunning && currentSpeedBytes > 1024) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, label = "")
                Box(modifier = Modifier.size(10.dp).background(dotColor, CircleShape))
            }
            Spacer(Modifier.height(16.dp))
            val lineColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path()
                val stepX = size.width / (points.size - 1)
                points.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = size.height - (v / maxPoint * size.height)
                    if (i == 0) path.moveTo(x, y) else {
                        val px = (i - 1) * stepX
                        val py = size.height - (points[i - 1] / maxPoint * size.height)
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
fun AddEditServerDialog(server: NxiwNetworkServer, onDismiss: () -> Unit, onSave: (NxiwNetworkServer) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(server.name) }
    var ip by remember { mutableStateOf(server.ip) }
    var pass by remember { mutableStateOf(server.password) }
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
                OutlinedTextField(value = pass, onValueChange = { pass = it.filter { c -> !c.isWhitespace() } }, label = { Text("Пароль от туннеля", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
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
