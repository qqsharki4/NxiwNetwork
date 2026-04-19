package com.tyrnguard.client.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyrnguard.client.SettingsStore
import com.tyrnguard.client.TunnelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun InfoTab() {
    var currentScreen by rememberSaveable { mutableStateOf("main") }

    BackHandler(enabled = currentScreen != "main") { currentScreen = "main" }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                val animationSpec = spring<IntOffset>(stiffness = Spring.StiffnessMedium)
                if (targetState != "main") {
                    slideInHorizontally(animationSpec) { it } + fadeIn() togetherWith slideOutHorizontally(animationSpec) { -it / 2 } + fadeOut()
                } else {
                    slideInHorizontally(animationSpec) { -it / 2 } + fadeIn() togetherWith slideOutHorizontally(animationSpec) { it } + fadeOut()
                }
            },
            label = "settings_navigation"
        ) { screen ->
            when (screen) {
                "main" -> MainSettingsMenu { currentScreen = it }
                "network" -> NetworkSettings { currentScreen = "main" }
                "performance" -> PerformanceSettings { currentScreen = "main" }
                "interface" -> InterfaceSettings { currentScreen = "main" }
            }
        }
    }
}

@Composable
fun MainSettingsMenu(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val currentPeer by settingsStore.peer.collectAsStateWithLifecycle("")
    var showImportantInfoDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        
        MenuCategoryItem("Сеть", "Протокол, MTU, DNS", Icons.Default.Language) { onNavigate("network") }
        MenuCategoryItem("Производительность", "Ключи, Потоки, Капча", Icons.Default.Speed) { onNavigate("performance") }
        MenuCategoryItem("Интерфейс", "Темы, Цвета, Отклик", Icons.Default.Palette) { onNavigate("interface") }
        
        CategoryCard("Синхронизация", Icons.Default.Share) {
            Text("Импорт запустит настройку по ссылке из буфера обмена.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(20.dp),
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (text.contains("tyrnguard://config?data=")) {
                            try {
                                val json = JSONObject(String(Base64.decode(text.substringAfter("data="), Base64.URL_SAFE)))
                                scope.launch { addServerToStoreDirect(context, settingsStore, json) }
                            } catch (e: Exception) { Toast.makeText(context, "Ошибка чтения ссылки", Toast.LENGTH_SHORT).show() }
                        } else Toast.makeText(context, "Ссылка не найдена в буфере", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Default.ContentPasteGo, null); Spacer(Modifier.width(8.dp)); Text("Импорт", fontSize = 16.sp)
                }

                FilledTonalButton(
                    modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(20.dp),
                    onClick = {
                        scope.launch {
                            val serversJson = settingsStore.savedServersJson.first()
                            if (currentPeer.isBlank() || serversJson.isBlank()) { 
                                Toast.makeText(context, "Сначала выберите сервер на главном экране", Toast.LENGTH_SHORT).show(); return@launch 
                            }
                            val servers = JSONArray(serversJson)
                            var activeObj: JSONObject? = null
                            for (i in 0 until servers.length()) { if (servers.getJSONObject(i).optString("ip") == currentPeer) { activeObj = servers.getJSONObject(i); break } }
                            if (activeObj == null) { Toast.makeText(context, "Активный сервер не найден", Toast.LENGTH_SHORT).show(); return@launch }
                            
                            val b64 = Base64.encodeToString(activeObj.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Конфигурация TyrnGuard:\n\ntyrnguard://config?data=$b64") }, "Поделиться конфигурацией"))
                        }
                    }
                ) {
                    Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(8.dp)); Text("Экспорт", fontSize = 16.sp)
                }
            }
        }
        CategoryCard("О приложении", Icons.Default.Info) {
            SettingClickRow(Icons.Default.HelpOutline, "Важная информация", "Справка по работе приложения") { showImportantInfoDialog = true }
            Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingClickRow(Icons.Default.Code, "GitHub (Форк)", "Исходный код этого приложения") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yzewe/TyrnGuard"))) }
            Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingClickRow(Icons.Default.CodeOff, "GitHub (Оригинал)", "Оригинальный репозиторий проекта") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/amurcanov/proxy-turn-vk-android"))) }
        }
        
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Версия 1.0.6 (Stable)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, fontSize = 14.sp)
            FilledTonalButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yzewe.ru"))) }, shape = RoundedCornerShape(16.dp)) {
                Text("Форк от yzewe", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
    if (showImportantInfoDialog) ImportantInfoDialog { showImportantInfoDialog = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val protocol by settingsStore.protocol.collectAsStateWithLifecycle("udp")
    val customMtu by settingsStore.customMtu.collectAsStateWithLifecycle(0)
    val dnsType by settingsStore.customDns.collectAsStateWithLifecycle("default")
    val customDnsIp by settingsStore.customDnsIp.collectAsStateWithLifecycle("1.1.1.1")
    val currentPeer by settingsStore.peer.collectAsStateWithLifecycle("")
    val currentHashes by settingsStore.vkHashes.collectAsStateWithLifecycle("")
    val workersCount by settingsStore.workersPerHash.collectAsStateWithLifecycle(24)

    var lastMtu by remember(customMtu) { mutableIntStateOf(customMtu) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsHeader("Сеть", onBack)
        CategoryCard("Транспорт", Icons.Default.CompareArrows) {
            Text("Сетевой протокол", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("udp" to "UDP", "tcp" to "TCP").forEachIndexed { i, (v, l) ->
                    SegmentedButton(
                        selected = protocol == v, shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); scope.launch { settingsStore.save(currentPeer, currentHashes, "", workersCount, v, 9000, "") } }
                    ) { Text(l, fontSize = 14.sp) }
                }
            }
            Divider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Размер пакета (MTU)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                AnimatedContent(targetState = customMtu, label = "") { mtu -> Text(if (mtu == 0) "Авто" else "$mtu", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Slider(
                value = if (customMtu == 0) 1279f else customMtu.toFloat(),
                onValueChange = {
                    val v = if (it < 1280f) 0 else it.roundToInt()
                    if (kotlin.math.abs(v - lastMtu) > 5 || (v == 0 && lastMtu != 0)) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastMtu = v }
                    scope.launch { settingsStore.saveCustomMtu(v) }
                },
                onValueChangeFinished = { scope.launch { TunnelManager.reloadWireGuard() } }, valueRange = 1279f..1500f
            )
            Text("Меньшее значение может помочь при плохой связи. Оптимально: 1280-1420.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        CategoryCard("DNS Сервер", Icons.Default.Dns) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("default" to "Авто", "adguard" to "AdGuard", "cloudflare" to "Cloudflare", "custom" to "Свой").forEachIndexed { i, (v, l) ->
                    SegmentedButton(
                        selected = dnsType == v, shape = SegmentedButtonDefaults.itemShape(index = i, count = 4),
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); scope.launch { settingsStore.saveCustomDns(v); TunnelManager.reloadWireGuard() } }
                    ) { Text(l, fontSize = 11.sp, maxLines = 1) }
                }
            }
            AnimatedVisibility(visible = dnsType == "custom", enter = expandVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeIn(), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut()) {
                OutlinedTextField(
                    value = customDnsIp,
                    onValueChange = { scope.launch { settingsStore.saveCustomDnsIp(it.trim()) } },
                    label = { Text("IP адрес DNS сервера") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    shape = RoundedCornerShape(20.dp)
                )
            }
            Text(when(dnsType) { "adguard" -> "Блокирует рекламу и трекеры на уровне пакетов."; "cloudflare" -> "Самый быстрый и приватный DNS."; "custom" -> "Впишите IP-адрес предпочитаемого DNS."; else -> "Использовать DNS провайдера или сервера." }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp), fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val workersCount by settingsStore.workersPerHash.collectAsStateWithLifecycle(24)
    val captchaMethod by settingsStore.captchaSolveMethod.collectAsStateWithLifecycle("manual")
    val currentHashesFromStore by settingsStore.vkHashes.collectAsStateWithLifecycle("")
    val protocol by settingsStore.protocol.collectAsStateWithLifecycle("udp")
    val currentPeer by settingsStore.peer.collectAsStateWithLifecycle("")

    var hashesList by remember { mutableStateOf(listOf("")) }
    LaunchedEffect(currentHashesFromStore) { hashesList = currentHashesFromStore.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") } }

    fun updateHashes(newList: List<String>) {
        hashesList = newList
        scope.launch { settingsStore.save(currentPeer, newList.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","), "", workersCount, protocol, 9000, "") }
    }
    
    var lastWorkerCount by remember(workersCount) { mutableIntStateOf(workersCount) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsHeader("Производительность", onBack)
        CategoryCard("VK Ключи (Hashes)", Icons.Default.VpnKey) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                hashesList.forEachIndexed { index, hash ->
                    OutlinedTextField(
                        value = hash,
                        onValueChange = { val l = hashesList.toMutableList(); l[index] = it; updateHashes(l) },
                        label = { Text("Ключ ${index + 1}") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), singleLine = true,
                        trailingIcon = { if (hashesList.size > 1) IconButton(onClick = { val l = hashesList.toMutableList(); l.removeAt(index); updateHashes(l) }) { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) } }
                    )
                }
                if (hashesList.size < 3 && hashesList.last().isNotEmpty()) {
                    FilledTonalButton(onClick = { updateHashes(hashesList + "") }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Добавить ключ")
                    }
                }
            }
        }
        CategoryCard("Нагрузка", Icons.Default.Memory) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Потоки обработки", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                AnimatedContent(targetState = workersCount, label = "") { wc -> Text("$wc", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Slider(
                value = workersCount.toFloat(),
                onValueChange = {
                    val clamped = ((it / 12).roundToInt() * 12).coerceIn(12, 72)
                    if (clamped != lastWorkerCount) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastWorkerCount = clamped }
                    scope.launch { settingsStore.save(currentPeer, currentHashesFromStore, "", clamped, protocol, 9000, "") }
                }, valueRange = 12f..72f, steps = 4
            )
            Text("Больше потоков — выше скорость, но сильнее расход батареи.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        CategoryCard("Решение капчи", Icons.Default.SmartToy) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("manual" to "WebView (Надежно)", "auto" to "RJS (Автомат)").forEachIndexed { i, (v, l) ->
                    SegmentedButton(
                        selected = captchaMethod == v, shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); scope.launch { settingsStore.saveCaptchaMode(if (v == "auto") "rjs" else "wv"); settingsStore.saveCaptchaSolveMethod(v) } }
                    ) { Text(l, fontSize = 14.sp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle("system")
    val dynamicColor by settingsStore.useDynamicColor.collectAsStateWithLifecycle(true)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsHeader("Интерфейс", onBack)
        CategoryCard("Внешний вид", Icons.Default.Palette) {
            Text("Тема оформления", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("system" to "Авто", "light" to "Светлая", "dark" to "Темная", "amoled" to "Amoled").forEachIndexed { i, (v, l) ->
                    SegmentedButton(
                        selected = themeMode == v, shape = SegmentedButtonDefaults.itemShape(index = i, count = 4),
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); scope.launch { settingsStore.saveThemeMode(v) } }
                    ) { Text(l, fontSize = 12.sp, maxLines = 1) }
                }
            }
            Divider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingSwitchRow(
                icon = Icons.Default.ColorLens,
                title = "Dynamic Colors",
                subtitle = "Использовать цвета обоев системы",
                checked = dynamicColor,
                enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                onCheckedChange = { scope.launch { settingsStore.saveDynamicColor(it) } }
            )
        }
    }
}

suspend fun addServerToStoreDirect(context: Context, settingsStore: SettingsStore, json: JSONObject) {
    val ip = json.optString("ip", "").trim()
    val name = json.optString("name", "Импортированный сервер").trim()
    val pass = json.optString("password", "").trim()
    if (ip.isBlank()) return

    val currentArray = try { JSONArray(settingsStore.savedServersJson.first()) } catch (e: Exception) { JSONArray() }
    var existsIdx = -1
    for (i in 0 until currentArray.length()) { if (currentArray.getJSONObject(i).optString("ip").trim() == ip) { existsIdx = i; break } }

    val newObj = JSONObject().apply { put("id", if (existsIdx != -1) currentArray.getJSONObject(existsIdx).getString("id") else UUID.randomUUID().toString()); put("name", name); put("ip", ip); put("password", pass) }
    if (existsIdx != -1) currentArray.put(existsIdx, newObj) else currentArray.put(newObj)
    settingsStore.saveServersList(currentArray.toString())

    withContext(Dispatchers.Main) { Toast.makeText(context, "Сервер '$name' ${if (existsIdx != -1) "обновлен" else "добавлен"}", Toast.LENGTH_SHORT).show() }
}

@Composable
fun MenuCategoryItem(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurface) }
        Spacer(Modifier.width(16.dp)); Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CategoryCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp)); Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            content()
        }
    }
}

@Composable
private fun SettingClickRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }.padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) { 
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) 
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SettingSwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp).alpha(if(enabled) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) { 
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) 
        }
        Switch(checked = checked, onCheckedChange = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onCheckedChange(it) }, enabled = enabled)
    }
}

@Composable
fun ImportantInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.padding(28.dp).verticalScroll(rememberScrollState())) {
                Text("Справка", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Text("• RJS-Капча — автоматическое решение (экспериментально). В случае проблем верните WebView.\n\n• Ссылки (tyrnguard://) при экспорте содержат пароль от туннеля. Не передавайте их третьим лицам.\n\n• Если туннель подключается, но интернета нет — обновите VK Ключи.", style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp, fontSize = 16.sp)
                Spacer(Modifier.height(32.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(24.dp)) { Text("Закрыть", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }
}