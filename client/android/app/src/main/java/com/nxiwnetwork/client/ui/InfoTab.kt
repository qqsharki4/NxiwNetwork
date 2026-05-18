package com.nxiwnetwork.client.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nxiwnetwork.client.AvailableUpdate
import com.nxiwnetwork.client.CoreBackend
import com.nxiwnetwork.client.ReleaseUpdater
import com.nxiwnetwork.client.filterChangelogForVersion
import com.nxiwnetwork.client.SettingsStore
import com.nxiwnetwork.client.TunnelManager
import com.nxiwnetwork.client.UpdateAvailableDialog
import com.nxiwnetwork.client.UpdateCheckCoordinator
import com.nxiwnetwork.client.UpdateDownloadState
import com.nxiwnetwork.client.formatUpdatePublishedAgo
import com.nxiwnetwork.client.normalizeVkHashFieldEdit
import com.nxiwnetwork.client.normalizeVkHashInput
import com.nxiwnetwork.client.normalizeVkHashList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

private const val VK_HASH_ROW_ANIMATION_MS = 300
private const val VK_HASH_TRASH_ANIMATION_MS = 320
private const val UPDATE_REMIND_LATER_MS = 24L * 60L * 60L * 1000L
private const val DEBUG_MENU_UNLOCK_TAPS = 3
private const val DEBUG_MENU_UNLOCK_WINDOW_MS = 1300L
private val SEGMENTED_CONTROL_HEIGHT = 40.dp

private fun <S> AnimatedContentTransitionScope<S>.noContentAnimation(): ContentTransform =
    (EnterTransition.None togetherWith ExitTransition.None) using
        SizeTransform(clip = false) { _, _ -> tween(0) }

@Composable
private fun SegmentedControlLoadSlot(
    loaded: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEGMENTED_CONTROL_HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        if (loaded) content()
    }
}

private data class VkHashFieldUi(
    val id: Long,
    val value: String
)

private enum class VkHashSlotType {
    Hidden,
    AddButton,
    Field
}

private data class VkHashSlotUi(
    val slotIndex: Int,
    val type: VkHashSlotType,
    val fieldId: Long? = null,
    val value: String = "",
    val labelIndex: Int = 0
) {
    val contentKey: String get() = "$slotIndex:$type"
}

private fun buildVkHashSlots(fields: List<VkHashFieldUi>): List<VkHashSlotUi> {
    val visibleFields = fields.take(3)
    val firstField = visibleFields.getOrNull(0)
    val secondField = visibleFields.getOrNull(1)
    val thirdField = visibleFields.getOrNull(2)

    return listOf(
        if (firstField != null) {
            VkHashSlotUi(
                slotIndex = 0,
                type = VkHashSlotType.Field,
                fieldId = firstField.id,
                value = firstField.value,
                labelIndex = 1
            )
        } else {
            VkHashSlotUi(slotIndex = 0, type = VkHashSlotType.Hidden)
        },
        if (secondField != null) {
            VkHashSlotUi(
                slotIndex = 1,
                type = VkHashSlotType.Field,
                fieldId = secondField.id,
                value = secondField.value,
                labelIndex = 2
            )
        } else {
            VkHashSlotUi(slotIndex = 1, type = VkHashSlotType.AddButton)
        },
        when {
            thirdField != null -> VkHashSlotUi(
                slotIndex = 2,
                type = VkHashSlotType.Field,
                fieldId = thirdField.id,
                value = thirdField.value,
                labelIndex = 3
            )
            secondField != null -> VkHashSlotUi(slotIndex = 2, type = VkHashSlotType.AddButton)
            else -> VkHashSlotUi(slotIndex = 2, type = VkHashSlotType.Hidden)
        }
    )
}

@Composable
private fun VkHashDeleteSlot(
    slotWidth: Dp,
    visible: Boolean,
    animate: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.width(slotWidth).clipToBounds(),
        contentAlignment = Alignment.CenterEnd
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = if (animate) {
                fadeIn(
                    tween(210, delayMillis = 80, easing = FastOutSlowInEasing)
                ) + scaleIn(
                    tween(250, delayMillis = 40, easing = FastOutSlowInEasing),
                    initialScale = 0.9f
                )
            } else {
                EnterTransition.None
            },
            exit = if (animate) {
                fadeOut(
                    tween(140, easing = FastOutSlowInEasing)
                ) + scaleOut(
                    tween(160, easing = FastOutSlowInEasing),
                    targetScale = 0.9f
                )
            } else {
                ExitTransition.None
            }
        ) {
            IconButton(
                enabled = enabled,
                onClick = onClick
            ) {
                Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun VkHashSlotContent(
    slot: VkHashSlotUi,
    fieldCount: Int,
    canAddHashField: Boolean,
    canEditHashFields: Boolean,
    animateInteractions: Boolean,
    onValueChange: (Long, String) -> Unit,
    onRemove: (Long) -> Unit,
    onAdd: () -> Unit
) {
    if (slot.type == VkHashSlotType.Hidden) {
        Spacer(Modifier.height(0.dp))
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (slot.slotIndex > 0) {
            Spacer(Modifier.height(12.dp))
        }

        when (slot.type) {
            VkHashSlotType.Field -> VkHashFieldRow(
                slot = slot,
                fieldCount = fieldCount,
                animateInteractions = animateInteractions,
                onValueChange = onValueChange,
                onRemove = onRemove
            )
            VkHashSlotType.AddButton -> VkHashAddButton(
                enabled = canAddHashField && canEditHashFields,
                onClick = onAdd
            )
            VkHashSlotType.Hidden -> Unit
        }
    }
}

@Composable
private fun VkHashFieldRow(
    slot: VkHashSlotUi,
    fieldCount: Int,
    animateInteractions: Boolean,
    onValueChange: (Long, String) -> Unit,
    onRemove: (Long) -> Unit
) {
    val fieldId = slot.fieldId ?: return
    val showDeleteButton = fieldCount > 1
    val deleteSlotWidth by animateDpAsState(
        targetValue = if (showDeleteButton) 56.dp else 0.dp,
        animationSpec = if (animateInteractions) {
            tween(VK_HASH_TRASH_ANIMATION_MS, easing = FastOutSlowInEasing)
        } else {
            tween(0)
        },
        label = "vk_hash_delete_slot"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = slot.value,
            onValueChange = { value -> onValueChange(fieldId, normalizeVkHashFieldEdit(value)) },
            label = { Text("Ключ ${slot.labelIndex}") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            singleLine = true
        )
        VkHashDeleteSlot(
            slotWidth = deleteSlotWidth,
            visible = showDeleteButton,
            animate = animateInteractions,
            enabled = true,
            onClick = { onRemove(fieldId) }
        )
    }
}

@Composable
private fun VkHashAddButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val containerColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "vk_hash_add_container"
    )
    val contentColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
        },
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "vk_hash_add_content"
    )
    val tonalElevation by animateDpAsState(
        targetValue = if (enabled) 2.dp else 0.dp,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "vk_hash_add_elevation"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Добавить ключ", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SegmentedSelectionIcon(
    selected: Boolean,
    animate: Boolean
) {
    if (animate) {
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(140, easing = FastOutSlowInEasing)) +
                scaleIn(tween(170, easing = FastOutSlowInEasing), initialScale = 0.86f),
            exit = fadeOut(tween(90, easing = FastOutSlowInEasing)) +
                scaleOut(tween(120, easing = FastOutSlowInEasing), targetScale = 0.86f)
        ) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
        }
    } else if (selected) {
        Icon(Icons.Default.Check, null, Modifier.size(18.dp))
    }
}

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
                "updates" -> UpdatesSettings { currentScreen = "main" }
            }
        }
    }
}

@Composable
fun MainSettingsMenu(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val appVersionName = remember(context) { readAppVersionName(context) }
    val versionTextInteractionSource = remember { MutableInteractionSource() }
    var showImportantInfoDialog by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var versionFirstTapAt by remember { mutableLongStateOf(0L) }

    fun handleVersionTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - versionFirstTapAt > DEBUG_MENU_UNLOCK_WINDOW_MS) {
            versionFirstTapAt = now
            versionTapCount = 0
        }

        versionTapCount += 1
        val remaining = DEBUG_MENU_UNLOCK_TAPS - versionTapCount

        if (remaining <= 0) {
            versionTapCount = 0
            versionFirstTapAt = 0L
            openDebugTools(context)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        
        MenuCategoryItem("Сеть", "Протокол, MTU, DNS", Icons.Default.Language) { onNavigate("network") }
        MenuCategoryItem("Производительность", "Ключи, Потоки, Капча", Icons.Default.Speed) { onNavigate("performance") }
        MenuCategoryItem("Интерфейс", "Темы, Цвета, Отклик", Icons.Default.Palette) { onNavigate("interface") }

        CategoryCard("О приложении", Icons.Default.Info) {
            SettingClickRow(Icons.Default.HelpOutline, "Важная информация", "Справка по работе приложения") { showImportantInfoDialog = true }
            Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingClickRow(Icons.Default.SystemUpdate, "Обновления", "Канал обновлений") { onNavigate("updates") }
            Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingClickRow(Icons.Default.Code, "GitHub", "Исходный код") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/qqsharki4/NxiwNetwork"))) }
        }
        
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Версия $appVersionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                fontSize = 14.sp,
                modifier = Modifier.clickable(
                    interactionSource = versionTextInteractionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = { handleVersionTap() }
                )
            )
        }
    }
    if (showImportantInfoDialog) ImportantInfoDialog { showImportantInfoDialog = false }
}

private fun openDebugTools(context: Context) {
    runCatching {
        context.startActivity(
            Intent().setClassName(
                context.packageName,
                "com.nxiwnetwork.client.DebugToolsActivity"
            )
        )
    }.onFailure {
        Toast.makeText(context, "Не удалось открыть debug tools", Toast.LENGTH_SHORT).show()
    }
}

private fun normalizeUpdateChannel(channel: String): String {
    return when (channel.lowercase()) {
        "pre", "dev" -> channel.lowercase()
        else -> "stable"
    }
}

private fun readAppVersionName(context: Context): String {
    return try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        packageInfo.versionName ?: "неизвестна"
    } catch (_: Exception) {
        "неизвестна"
    }
}

private fun readAssetText(context: Context, fileName: String): String {
    return runCatching {
        context.assets.open(fileName).bufferedReader().use { it.readText() }
    }.getOrElse {
        "Не удалось открыть $fileName"
    }
}

private fun formatUpdateCheckTime(lastCheckAtMillis: Long, nowMillis: Long): String {
    if (lastCheckAtMillis <= 0L) return "Последняя проверка: никогда"
    val elapsedMillis = (nowMillis - lastCheckAtMillis).coerceAtLeast(0L)
    if (elapsedMillis <= 20_000L) return "Последняя проверка: только что"
    if (elapsedMillis < 60_000L) return "Последняя проверка: меньше минуты назад"

    val elapsedMinutes = elapsedMillis / 60_000L

    val days = elapsedMinutes / (24L * 60L)
    val hours = (elapsedMinutes % (24L * 60L)) / 60L
    val minutes = elapsedMinutes % 60L
    val parts = buildList {
        if (days > 0L) add("$days ${pluralRu(days, "день", "дня", "дней")}")
        if (hours > 0L) add("$hours ${pluralRu(hours, "час", "часа", "часов")}")
        if (minutes > 0L) add("$minutes ${pluralRu(minutes, "минуту", "минуты", "минут")}")
    }
    val relativeTime = parts.joinToString(" ")
    return "Последняя проверка: $relativeTime назад"
}

private fun formatRateLimitAlert(rateLimitUntilMillis: Long, nowMillis: Long): String? {
    if (rateLimitUntilMillis <= nowMillis) return null
    val remainingMinutes = (((rateLimitUntilMillis - nowMillis).coerceAtLeast(0L)) + 59_999L) / 60_000L
    return "GitHub ограничил проверки на ${formatDurationMinutes(remainingMinutes)}."
}

private fun formatDurationMinutes(totalMinutes: Long): String {
    val minutesValue = totalMinutes.coerceAtLeast(1L)
    if (minutesValue < 60L) return "$minutesValue ${pluralRu(minutesValue, "минуту", "минуты", "минут")}"

    val hours = minutesValue / 60L
    val minutes = minutesValue % 60L
    return buildList {
        add("$hours ${pluralRu(hours, "час", "часа", "часов")}")
        if (minutes > 0L) add("$minutes ${pluralRu(minutes, "минуту", "минуты", "минут")}")
    }.joinToString(" ")
}

private fun pluralRu(value: Long, one: String, few: String, many: String): String {
    val lastTwoDigits = value % 100L
    if (lastTwoDigits in 11L..14L) return many

    return when (value % 10L) {
        1L -> one
        2L, 3L, 4L -> few
        else -> many
    }
}

private fun buildRemoteChangelogSeenKey(update: AvailableUpdate): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(update.body.toByteArray()).joinToString("") { "%02x".format(it) }
    return "${update.tagName}:$hash"
}

private suspend fun downloadUpdate(
    context: Context,
    settingsStore: SettingsStore,
    channel: String,
    update: AvailableUpdate
) {
    settingsStore.saveUpdateStatus("Скачиваем ${update.tagName}...")
    UpdateCheckCoordinator.setDownloadProgress(update.tagName, 0, "Скачано 0%")
    val result = runCatching {
        ReleaseUpdater.downloadUpdateFile(context, update) { progress ->
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
    if (result.isSuccess) {
        settingsStore.saveUpdateLaterUntil(channel, 0L)
        settingsStore.saveSkippedUpdateTag(channel, "")
    }
}

private suspend fun installDownloadedUpdate(
    context: Context,
    update: AvailableUpdate
) {
    if (!ReleaseUpdater.isUpdateDownloaded(context, update)) {
        return
    }
    if (!ReleaseUpdater.canInstallDownloadedApks(context)) {
        withContext(Dispatchers.Main) {
            ReleaseUpdater.openInstallPermissionSettings(context)
        }
        return
    }

    runCatching {
        withContext(Dispatchers.Main) {
            ReleaseUpdater.installDownloadedApk(context, ReleaseUpdater.downloadedUpdateFile(context, update))
        }
    }
}

@Composable
private fun AvailableUpdateRow(
    currentVersionName: String,
    update: AvailableUpdate,
    nowMillis: Long,
    downloadState: UpdateDownloadState?,
    onOpen: () -> Unit
) {
    val state = downloadState?.takeIf { it.tagName == update.tagName }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    maxLines = 1
                )
            }
            Text(
                formatUpdatePublishedAgo(update.publishedAtMillis, nowMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state?.let {
                if (it.progressPercent == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { it.progressPercent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            FilledTonalButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(
                    Icons.Default.Article,
                    null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Открыть обновление", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember(context) { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val selectedChannelOrNull by remember(settingsStore) {
        settingsStore.updateChannel.map { normalizeUpdateChannel(it) }
    }.collectAsStateWithLifecycle(null)
    val updateLastCheckAt by settingsStore.updateLastCheckAt.collectAsStateWithLifecycle(0L)
    val updateRateLimitUntil by settingsStore.updateRateLimitUntil.collectAsStateWithLifecycle(0L)
    val checkingUpdates by UpdateCheckCoordinator.isChecking.collectAsStateWithLifecycle()
    val availableUpdates by UpdateCheckCoordinator.availableUpdates.collectAsStateWithLifecycle()
    val downloadState by UpdateCheckCoordinator.downloadState.collectAsStateWithLifecycle()
    val selected = selectedChannelOrNull ?: "stable"
    val appVersionName = remember(context) { readAppVersionName(context) }
    val installedVersion = remember(appVersionName) { ReleaseUpdater.parseVersion(appVersionName) }
    val newerUpdates = remember(availableUpdates, installedVersion) {
        availableUpdates
            .filter { installedVersion == null || it.version > installedVersion }
            .sortedByDescending { it.version }
    }
    val displayedUpdates = remember(newerUpdates, selected) {
        newerUpdates
            .filter { ReleaseUpdater.updateMatchesChannel(it, selected) }
    }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var dialogUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var animateUpdateChanges by remember { mutableStateOf(false) }
    val rateLimitAlert = formatRateLimitAlert(updateRateLimitUntil, nowMillis)

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000L)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsHeader("Обновления", onBack)

        CategoryCard("Канал обновлений", Icons.Default.SystemUpdate, animateSize = animateUpdateChanges) {
            SegmentedControlLoadSlot(loaded = selectedChannelOrNull != null) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().height(SEGMENTED_CONTROL_HEIGHT)
                ) {
                    listOf("stable" to "Stable", "pre" to "Pre", "dev" to "Dev").forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = selected == value,
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                animateUpdateChanges = true
                                scope.launch { settingsStore.saveUpdateChannel(value) }
                            }
                        ) {
                            Text(label, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            AnimatedVisibility(
                visible = selected != "dev",
                enter = if (animateUpdateChanges) fadeIn(tween(140)) + expandVertically(tween(180, easing = FastOutSlowInEasing)) else EnterTransition.None,
                exit = if (animateUpdateChanges) fadeOut(tween(100)) + shrinkVertically(tween(160, easing = FastOutSlowInEasing)) else ExitTransition.None
            ) {
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        if (animateUpdateChanges) {
                            fadeIn(tween(140, delayMillis = 30)) togetherWith fadeOut(tween(100))
                        } else {
                            noContentAnimation()
                        }
                    },
                    label = "update_channel_description"
                ) { channel ->
                    Text(
                        updateChannelDescription(channel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                }
            }
            AnimatedVisibility(
                visible = selected == "dev",
                enter = if (animateUpdateChanges) fadeIn(tween(140)) + expandVertically(tween(180, easing = FastOutSlowInEasing)) else EnterTransition.None,
                exit = if (animateUpdateChanges) fadeOut(tween(100)) + shrinkVertically(tween(160, easing = FastOutSlowInEasing)) else ExitTransition.None
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Dev ветка может часто ломаться, обновляться слишком часто и содержать незавершенные изменения.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
        }

        CategoryCard("Проверка обновлений", Icons.Default.Update, animateSize = animateUpdateChanges) {
            Text(
                formatUpdateCheckTime(updateLastCheckAt, nowMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                fontSize = 14.sp
            )
            AnimatedVisibility(
                visible = rateLimitAlert != null,
                enter = if (animateUpdateChanges) fadeIn(tween(140)) + expandVertically(tween(180, easing = FastOutSlowInEasing)) else EnterTransition.None,
                exit = if (animateUpdateChanges) fadeOut(tween(100)) + shrinkVertically(tween(160, easing = FastOutSlowInEasing)) else ExitTransition.None
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        rateLimitAlert.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    animateUpdateChanges = true
                    UpdateCheckCoordinator.requestManualCheck(context, settingsStore, selected)
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                if (checkingUpdates) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Проверить обновления", fontWeight = FontWeight.Bold)
            }
        }

        CategoryCard("Доступные обновления", Icons.Default.NewReleases, animateSize = animateUpdateChanges) {
            AnimatedVisibility(
                visible = checkingUpdates,
                enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                    expandVertically(tween(220, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(120)) +
                    shrinkVertically(tween(180, easing = FastOutSlowInEasing))
            ) {
                Column {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                }
            }

            AnimatedContent(
                targetState = displayedUpdates,
                transitionSpec = {
                    if (animateUpdateChanges) {
                        fadeIn(animationSpec = tween(durationMillis = 160, delayMillis = 40)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 120)) using
                            SizeTransform(clip = false) { _, _ ->
                                tween(durationMillis = 220, easing = FastOutSlowInEasing)
                            }
                    } else {
                        noContentAnimation()
                    }
                },
                label = "available_updates_content"
            ) { updates ->
                if (updates.isEmpty()) {
                    Text(
                        "Нет доступных обновлений.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        updates.forEach { update ->
                            key(update.tagName) {
                                AvailableUpdateRow(
                                    currentVersionName = appVersionName,
                                    update = update,
                                    nowMillis = nowMillis,
                                    downloadState = downloadState,
                                    onOpen = {
                                        dialogUpdate = update
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        CategoryCard("История изменений", Icons.Default.History) {
            SettingClickRow(Icons.Default.History, "Changelog", "История изменений текущей ветки") { showChangelogDialog = true }
        }
    }

    if (showChangelogDialog) ChangelogDialog { showChangelogDialog = false }
    dialogUpdate?.let { update ->
        UpdateAvailableDialog(
            update = update,
            currentVersionName = appVersionName,
            isDownloaded = ReleaseUpdater.isUpdateDownloaded(context, update),
            downloadState = downloadState,
            onClose = {
                dialogUpdate = null
            },
            onLater = {
                scope.launch {
                    settingsStore.saveRemoteChangelogSeenKey(selected, buildRemoteChangelogSeenKey(update))
                    settingsStore.saveUpdateLaterUntil(selected, System.currentTimeMillis() + UPDATE_REMIND_LATER_MS)
                    settingsStore.saveUpdateStatus("Обновление ${update.tagName} отложено")
                }
                dialogUpdate = null
            },
            onSkip = {
                scope.launch {
                    settingsStore.saveRemoteChangelogSeenKey(selected, buildRemoteChangelogSeenKey(update))
                    settingsStore.saveSkippedUpdateTag(selected, update.tagName)
                    settingsStore.saveUpdateStatus("Пропущена версия ${update.tagName}")
                }
                dialogUpdate = null
            },
            onDownload = {
                scope.launch {
                    settingsStore.saveRemoteChangelogSeenKey(selected, buildRemoteChangelogSeenKey(update))
                    downloadUpdate(context, settingsStore, selected, update)
                }
            },
            onInstall = {
                scope.launch {
                    installDownloadedUpdate(context, update)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val protocolOrNull by remember(settingsStore) {
        settingsStore.protocol.map { if (it == "tcp") "tcp" else "udp" }
    }.collectAsStateWithLifecycle(null)
    val customMtu by settingsStore.customMtu.collectAsStateWithLifecycle(0)
    val dnsTypeOrNull by remember(settingsStore) {
        settingsStore.customDns.map { dns ->
            when (dns) {
                "adguard", "cloudflare", "custom" -> dns
                else -> "default"
            }
        }
    }.collectAsStateWithLifecycle(null)
    val customDnsIp by settingsStore.customDnsIp.collectAsStateWithLifecycle("1.1.1.1")
    val protocol = protocolOrNull ?: "udp"
    val dnsType = dnsTypeOrNull ?: "default"

    var lastMtu by remember(customMtu) { mutableIntStateOf(customMtu) }
    var animateProtocolSelection by remember { mutableStateOf(false) }
    var animateMtuValue by remember { mutableStateOf(false) }
    var animateDnsSelection by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsHeader("Сеть", onBack)
        CategoryCard("Транспорт", Icons.Default.CompareArrows, animateSize = false) {
            Text("Сетевой протокол", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            SegmentedControlLoadSlot(loaded = protocolOrNull != null) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().height(SEGMENTED_CONTROL_HEIGHT)
                ) {
                    listOf("udp" to "UDP", "tcp" to "TCP").forEachIndexed { i, (v, l) ->
                        val selected = protocol == v
                        SegmentedButton(
                            selected = selected,
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                            icon = { SegmentedSelectionIcon(selected, animateProtocolSelection) },
                            onClick = {
                                if (protocol != v) animateProtocolSelection = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch { settingsStore.saveProtocol(v) }
                            }
                        ) { Text(l, fontSize = 14.sp) }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Размер пакета (MTU)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                AnimatedContent(
                    targetState = customMtu,
                    transitionSpec = {
                        if (animateMtuValue) {
                            fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                        } else {
                            noContentAnimation()
                        }
                    },
                    label = "mtu_value"
                ) { mtu -> Text(if (mtu == 0) "Авто" else "$mtu", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Slider(
                value = if (customMtu == 0) 1279f else customMtu.toFloat(),
                onValueChange = {
                    val v = if (it < 1280f) 0 else it.roundToInt()
                    if (kotlin.math.abs(v - lastMtu) > 5 || (v == 0 && lastMtu != 0)) { animateMtuValue = true; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastMtu = v }
                    scope.launch { settingsStore.saveCustomMtu(v) }
                },
                onValueChangeFinished = { scope.launch { TunnelManager.reloadWireGuard() } }, valueRange = 1279f..1500f
            )
            Text("Меньшее значение может помочь при плохой связи. Оптимально: 1280-1420.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        CategoryCard("DNS Сервер", Icons.Default.Dns, animateSize = animateDnsSelection) {
            SegmentedControlLoadSlot(loaded = dnsTypeOrNull != null) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().height(SEGMENTED_CONTROL_HEIGHT)
                ) {
                    listOf("default" to "Авто", "adguard" to "AdGuard", "cloudflare" to "Cloudflare", "custom" to "Свой").forEachIndexed { i, (v, l) ->
                        val selected = dnsType == v
                        SegmentedButton(
                            selected = selected,
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 4),
                            icon = { SegmentedSelectionIcon(selected, animateDnsSelection) },
                            onClick = {
                                if (dnsType != v) animateDnsSelection = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch { settingsStore.saveCustomDns(v); TunnelManager.reloadWireGuard() }
                            }
                        ) { Text(l, fontSize = 11.sp, maxLines = 1) }
                    }
                }
            }
            AnimatedVisibility(
                visible = dnsType == "custom",
                enter = if (animateDnsSelection) expandVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeIn() else EnterTransition.None,
                exit = if (animateDnsSelection) shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut() else ExitTransition.None
            ) {
                OutlinedTextField(
                    value = customDnsIp,
                    onValueChange = { scope.launch { settingsStore.saveCustomDnsIp(it.trim()) } },
                    label = { Text("IP адрес DNS сервера") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    shape = RoundedCornerShape(20.dp)
                )
            }
            AnimatedContent(
                targetState = dnsType,
                transitionSpec = {
                    if (animateDnsSelection) {
                        fadeIn(tween(140, delayMillis = 30)) togetherWith fadeOut(tween(100))
                    } else {
                        noContentAnimation()
                    }
                },
                label = "dns_description",
                modifier = Modifier.padding(top = 12.dp)
            ) { selectedDns ->
                Text(
                    when (selectedDns) {
                        "adguard" -> "Блокирует рекламу и трекеры на уровне пакетов."
                        "cloudflare" -> "Самый быстрый и приватный DNS."
                        "custom" -> "Впишите IP-адрес предпочитаемого DNS."
                        else -> "Использовать DNS провайдера или сервера."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
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
    val lifecycleOwner = LocalLifecycleOwner.current

    val workersCount by settingsStore.workersPerHash.collectAsStateWithLifecycle(12)
    val captchaMethodOrNull by remember(settingsStore) {
        settingsStore.captchaSolveMethod.map { method ->
            if (captchaMethodOptions.any { it.first == method }) method else "manual"
        }
    }.collectAsStateWithLifecycle(null)
    val currentHashesFromStore by settingsStore.vkHashes.collectAsStateWithLifecycle("")
    val wifiHighPerformance by settingsStore.wifiHighPerformance.collectAsStateWithLifecycle(true)
    val keepaliveSeconds by settingsStore.clientKeepaliveSeconds.collectAsStateWithLifecycle(10)
    val coreBackendOrNull by settingsStore.coreBackend.collectAsStateWithLifecycle(null)
    val manualCaptchaOverlay by settingsStore.manualCaptchaOverlay.collectAsStateWithLifecycle(false)
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val activeCoreBackend by TunnelManager.activeCoreBackend.collectAsStateWithLifecycle()
    val captchaMethod = captchaMethodOrNull ?: "manual"
    val coreBackend = CoreBackend.fromId(coreBackendOrNull)
    var overlayPermissionRefresh by remember { mutableIntStateOf(0) }
    val overlayPermissionGranted = remember(overlayPermissionRefresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || AndroidSettings.canDrawOverlays(context)
    }

    var nextHashFieldId by remember { mutableLongStateOf(1L) }
    var hashFields by remember { mutableStateOf(listOf(VkHashFieldUi(id = 0L, value = ""))) }
    var lastWrittenHashes by remember { mutableStateOf<String?>(null) }
    var animateHashFields by remember { mutableStateOf(false) }

    fun createHashField(value: String): VkHashFieldUi {
        val field = VkHashFieldUi(id = nextHashFieldId, value = value)
        nextHashFieldId += 1L
        return field
    }

    fun hashFieldsToStorage(fields: List<VkHashFieldUi>): String {
        return fields
            .map { normalizeVkHashInput(it.value) }
            .filter { it.isNotEmpty() }
            .joinToString(",")
    }

    fun setHashFields(newFields: List<VkHashFieldUi>) {
        hashFields = newFields.take(3).ifEmpty { listOf(createHashField("")) }
    }

    fun removeHashField(fieldId: Long) {
        if (hashFields.size <= 1) return
        animateHashFields = true
        setHashFields(hashFields.filterNot { it.id == fieldId })
    }

    fun addHashField() {
        if (hashFields.size >= 3 || hashFields.lastOrNull()?.value.isNullOrBlank()) return
        animateHashFields = true
        setHashFields(hashFields + createHashField(""))
    }

    LaunchedEffect(currentHashesFromStore) {
        val storeHashes = normalizeVkHashList(currentHashesFromStore)

        if (lastWrittenHashes == storeHashes) {
            lastWrittenHashes = null
            return@LaunchedEffect
        }

        if (storeHashes != hashFieldsToStorage(hashFields)) {
            hashFields = storeHashes
                .split(",")
                .filter { it.isNotEmpty() }
                .take(3)
                .map { createHashField(it) }
                .ifEmpty { listOf(createHashField("")) }
        }
    }

    LaunchedEffect(hashFields, currentHashesFromStore) {
        val persistedHashes = hashFieldsToStorage(hashFields)
        val storeHashes = normalizeVkHashList(currentHashesFromStore)
        if (persistedHashes == storeHashes) return@LaunchedEffect

        delay(300)
        lastWrittenHashes = persistedHashes
        settingsStore.saveVkHashes(persistedHashes)
    }
    
    var lastWorkerCount by remember(workersCount) { mutableIntStateOf(workersCount) }
    var lastKeepaliveSeconds by remember(keepaliveSeconds) { mutableIntStateOf(keepaliveSeconds) }
    var animateWorkersCount by remember { mutableStateOf(false) }
    var animateKeepaliveValue by remember { mutableStateOf(false) }
    var animateCaptchaSelection by remember { mutableStateOf(false) }
    var animateCoreBackendSelection by remember { mutableStateOf(false) }

    fun openOverlayPermissionSettings() {
        overlayPermissionRefresh++
        runCatching {
            context.startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(context, "Не удалось открыть настройки overlay-разрешения", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayPermissionRefresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsHeader("Производительность", onBack)
        CategoryCard("VK Ключи (Hashes)", Icons.Default.VpnKey, animateSize = false) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Top
            ) {
                val slots = buildVkHashSlots(hashFields)
                val canAddHashField = hashFields.size < 3 && hashFields.lastOrNull()?.value?.isNotBlank() == true

                slots.forEach { slot ->
                    key(slot.slotIndex) {
                        AnimatedContent(
                            targetState = slot,
                            transitionSpec = {
                                if (animateHashFields) {
                                    (
                                        fadeIn(
                                            animationSpec = tween(180, delayMillis = 45, easing = FastOutSlowInEasing)
                                        ) + slideInVertically(
                                            animationSpec = tween(VK_HASH_ROW_ANIMATION_MS, easing = FastOutSlowInEasing)
                                        ) { it / 10 } + scaleIn(
                                            animationSpec = tween(VK_HASH_ROW_ANIMATION_MS, easing = FastOutSlowInEasing),
                                            initialScale = 0.985f
                                        )
                                    ) togetherWith (
                                        fadeOut(
                                            animationSpec = tween(140, easing = FastOutSlowInEasing)
                                        ) + slideOutVertically(
                                            animationSpec = tween(220, easing = FastOutSlowInEasing)
                                        ) { -it / 12 } + scaleOut(
                                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                                            targetScale = 0.985f
                                        )
                                    ) using SizeTransform(clip = false) { _, _ ->
                                        tween(VK_HASH_ROW_ANIMATION_MS, easing = FastOutSlowInEasing)
                                    }
                                } else {
                                    noContentAnimation()
                                }
                            },
                            contentKey = { it.contentKey },
                            label = "vk_hash_slot_${slot.slotIndex}"
                        ) { targetSlot ->
                            VkHashSlotContent(
                                slot = targetSlot,
                                fieldCount = hashFields.size,
                                canAddHashField = canAddHashField,
                                canEditHashFields = true,
                                animateInteractions = animateHashFields,
                                onValueChange = { fieldId, value ->
                                    setHashFields(
                                        hashFields.map {
                                            if (it.id == fieldId) it.copy(value = value) else it
                                        }
                                    )
                                },
                                onRemove = { fieldId -> removeHashField(fieldId) },
                                onAdd = { addHashField() }
                            )
                        }
                    }
                }
            }
        }
        CategoryCard("Нагрузка", Icons.Default.Memory, animateSize = animateWorkersCount) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Потоки обработки", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                AnimatedContent(
                    targetState = workersCount,
                    transitionSpec = {
                        if (animateWorkersCount) {
                            fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                        } else {
                            noContentAnimation()
                        }
                    },
                    label = "workers_count_value"
                ) { wc -> Text("$wc", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Slider(
                value = workersCount.toFloat(),
                onValueChange = {
                    val clamped = it.roundToInt().coerceIn(1, 72)
                    if (clamped != lastWorkerCount) { animateWorkersCount = true; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastWorkerCount = clamped }
                    scope.launch { settingsStore.saveWorkersPerHash(clamped) }
                },
                valueRange = 1f..72f
            )
            AnimatedVisibility(
                visible = workersCount < 12,
                enter = if (animateWorkersCount) fadeIn(tween(140)) + expandVertically(tween(180, easing = FastOutSlowInEasing)) else EnterTransition.None,
                exit = if (animateWorkersCount) fadeOut(tween(100)) + shrinkVertically(tween(160, easing = FastOutSlowInEasing)) else ExitTransition.None
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Ниже 12 потоков заметно режет скорость, но может снизить нагрев и расход батареи.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
            Text("Больше потоков — выше скорость, но сильнее расход батареи.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        CategoryCard("Ядро клиента", Icons.Default.DeveloperBoard, animateSize = animateCoreBackendSelection) {
            SegmentedControlLoadSlot(loaded = coreBackendOrNull != null) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().height(SEGMENTED_CONTROL_HEIGHT)
                ) {
                    CoreBackend.selectable.forEachIndexed { index, backend ->
                        val selected = coreBackend == backend
                        SegmentedButton(
                            selected = selected,
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = CoreBackend.selectable.size),
                            icon = { SegmentedSelectionIcon(selected, animateCoreBackendSelection) },
                            onClick = {
                                if (!selected) {
                                    animateCoreBackendSelection = true
                                    if (tunnelRunning && activeCoreBackend != null && backend != activeCoreBackend) {
                                        Toast.makeText(
                                            context,
                                            "Изменения применятся после перезапуска туннеля",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch { settingsStore.saveCoreBackend(backend.id) }
                            }
                        ) { Text(backend.label, fontSize = 14.sp) }
                    }
                }
            }
        }
        CategoryCard("Энергия и фон", Icons.Default.BatterySaver) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Высокая производительность Wi-Fi", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Держит Wi-Fi в low-latency режиме. Стабильнее, но может греть телефон.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                Switch(
                    checked = wifiHighPerformance,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveWifiHighPerformance(it) }
                    }
                )
            }
            Divider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Keepalive клиента", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                AnimatedContent(
                    targetState = keepaliveSeconds,
                    transitionSpec = {
                        if (animateKeepaliveValue) {
                            fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                        } else {
                            noContentAnimation()
                        }
                    },
                    label = "keepalive_value"
                ) { seconds -> Text("$seconds сек", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Slider(
                value = keepaliveSeconds.toFloat(),
                onValueChange = {
                    val seconds = it.roundToInt().coerceIn(5, 60)
                    if (seconds != lastKeepaliveSeconds) { animateKeepaliveValue = true; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastKeepaliveSeconds = seconds }
                    scope.launch { settingsStore.saveClientKeepaliveSeconds(seconds) }
                },
                valueRange = 5f..60f
            )
            Text("Больше интервал — меньше фоновой активности. Если соединение начинает засыпать, верни 10 секунд.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        CategoryCard("Решение капчи", Icons.Default.SmartToy) {
            SegmentedControlLoadSlot(loaded = captchaMethodOrNull != null) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().height(SEGMENTED_CONTROL_HEIGHT)
                ) {
                    captchaMethodOptions.forEachIndexed { i, (v, l) ->
                        val selected = captchaMethod == v || (captchaMethod == "auto" && v == "rjs_classic")
                        SegmentedButton(
                            selected = selected,
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = captchaMethodOptions.size),
                            icon = { SegmentedSelectionIcon(selected, animateCaptchaSelection) },
                            onClick = {
                                if (!selected) animateCaptchaSelection = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch { settingsStore.saveCaptchaMode(captchaModeForMethod(v)); settingsStore.saveCaptchaSolveMethod(v) }
                            }
                        ) { Text(l, fontSize = 14.sp) }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Окно поверх приложений", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Ручная WebView-капча откроется отдельным окном, даже если приложение свернуто.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
                Switch(
                    checked = manualCaptchaOverlay,
                    onCheckedChange = { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { settingsStore.saveManualCaptchaOverlay(enabled) }
                        if (enabled && !overlayPermissionGranted) {
                            openOverlayPermissionSettings()
                        }
                    }
                )
            }
            AnimatedVisibility(
                visible = manualCaptchaOverlay && !overlayPermissionGranted,
                enter = fadeIn(tween(140)) + expandVertically(tween(180, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(160, easing = FastOutSlowInEasing))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Нужно разрешение Android «поверх других окон». Без него капча откроется старым способом.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                    TextButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        openOverlayPermissionSettings()
                    }) {
                        Text("Открыть")
                    }
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

    val themeModeOrNull by remember(settingsStore) {
        settingsStore.themeMode.map { mode ->
            when (mode) {
                "light", "dark", "amoled" -> mode
                else -> "system"
            }
        }
    }.collectAsStateWithLifecycle(null)
    val dynamicColor by settingsStore.useDynamicColor.collectAsStateWithLifecycle(true)
    val themeMode = themeModeOrNull ?: "system"
    var animateThemeSelection by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsHeader("Интерфейс", onBack)
        CategoryCard("Внешний вид", Icons.Default.Palette, animateSize = false) {
            Text("Тема оформления", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            SegmentedControlLoadSlot(loaded = themeModeOrNull != null) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().height(SEGMENTED_CONTROL_HEIGHT)
                ) {
                    listOf("system" to "Авто", "light" to "Светлая", "dark" to "Темная", "amoled" to "Amoled").forEachIndexed { i, (v, l) ->
                        val selected = themeMode == v
                        SegmentedButton(
                            selected = selected,
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 4),
                            icon = { SegmentedSelectionIcon(selected, animateThemeSelection) },
                            onClick = {
                                if (themeMode != v) animateThemeSelection = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch { settingsStore.saveThemeMode(v) }
                            }
                        ) { Text(l, fontSize = 12.sp, maxLines = 1) }
                    }
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
private fun CategoryCard(
    title: String,
    icon: ImageVector,
    animateSize: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = if (animateSize) {
        Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
    } else {
        Modifier.fillMaxWidth()
    }

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = cardModifier
    ) {
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

private fun updateChannelDescription(channel: String): String {
    return when (normalizeUpdateChannel(channel)) {
        "dev" -> "Весь мусор от разработчика. Не рекомендуется ставить."
        "pre" -> "Тестовые pre-release сборки, тестируемые перед релизом."
        else -> "Только стабильные релизы."
    }
}

@Composable
fun ImportantInfoDialog(onDismiss: () -> Unit) {
    AssetMarkdownDialog(title = "Справка", assetName = "HELP.md", onDismiss = onDismiss)
}

@Composable
fun ChangelogDialog(
    markdownOverride: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appVersionName = remember(context) { readAppVersionName(context) }
    val changelog = remember(context, appVersionName, markdownOverride) {
        markdownOverride ?: filterChangelogForVersion(readAssetText(context, "CHANGELOG.md"), appVersionName)
    }

    MarkdownDialog(title = "История изменений", markdown = changelog, onDismiss = onDismiss)
}

@Composable
private fun AssetMarkdownDialog(title: String, assetName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val markdown = remember(context, assetName) { readAssetText(context, assetName) }

    MarkdownDialog(title = title, markdown = markdown, onDismiss = onDismiss)
}

@Composable
private fun MarkdownDialog(title: String, markdown: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.padding(28.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    MarkdownContent(markdown)
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(24.dp)) {
                    Text("Закрыть", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun MarkdownContent(markdown: String) {
    markdown.lineSequence().forEach { line ->
        when {
            line.startsWith("# ") -> Unit
            line.startsWith("## ") -> {
                Text(
                    line.removePrefix("## ").trim(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                )
            }
            line.startsWith("- ") -> {
                Text(
                    "• ${line.removePrefix("- ").trim()}",
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            line.isBlank() -> Spacer(Modifier.height(4.dp))
            else -> {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
    }
}
