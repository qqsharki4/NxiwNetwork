package com.nxiwnetwork.client.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nxiwnetwork.client.SettingsStore
import com.nxiwnetwork.client.TunnelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
data class AppItem(
    val name: String,
    val packageName: String,
    val isSystem: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExceptionsTab() {
    val context = LocalContext.current.applicationContext
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }

    val savedExcluded by settingsStore.excludedApps.collectAsStateWithLifecycle("")
    val selectedPackages = remember(savedExcluded) { savedExcluded.split(",").filter { it.isNotEmpty() }.toSet() }

    var appsList by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    val isWhitelist by settingsStore.isWhitelist.collectAsStateWithLifecycle(false)

    LaunchedEffect(Unit) {
        if (appsList.isNotEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val list = mutableListOf<AppItem>()
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            installedApps.forEach { app ->
                val hasLauncher = pm.getLaunchIntentForPackage(app.packageName) != null
                val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (hasLauncher && app.packageName != context.packageName && !app.packageName.contains("vkontakte")) {
                    list.add(AppItem(app.loadLabel(pm).toString(), app.packageName, isSys))
                }
            }
            appsList = list
        }
        isLoading = false
    }

    val filteredApps by remember(searchQuery, appsList, showSystemApps) {
        derivedStateOf {
            appsList.filter { (showSystemApps || !it.isSystem) && (searchQuery.isBlank() || it.name.contains(searchQuery, true) || it.packageName.contains(searchQuery, true)) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Роутинг", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("Выбрано: ${selectedPackages.size}", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
            }
        }

        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text("Поиск приложений...", fontSize = 16.sp) }, modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(20.dp), leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true
        )

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Показывать системные", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = showSystemApps, onCheckedChange = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); showSystemApps = it })
        }

        Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Режим работы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (isWhitelist) "Белый список: Отмеченные пускаются в VPN" else "Черный список: Отмеченные исключаются", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !isWhitelist, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); if (isWhitelist) scope.launch { settingsStore.saveIsWhitelist(false); delay(300); TunnelManager.reloadWireGuard() } }
                    ) { Text("ЧС") }
                    SegmentedButton(
                        selected = isWhitelist, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); if (!isWhitelist) scope.launch { settingsStore.saveIsWhitelist(true); delay(300); TunnelManager.reloadWireGuard() } }
                    ) { Text("БС") }
                }
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isSelected = selectedPackages.contains(app.packageName)
                    AppRow(app, isSelected) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val newList = if (isSelected) selectedPackages - app.packageName else selectedPackages + app.packageName
                        scope.launch { settingsStore.saveExcludedApps(newList.joinToString(",")); TunnelManager.reloadWireGuard() }
                    }
                }
            }
        }
    }
}

@Composable
fun AppRow(app: AppItem, isSelected: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val context = LocalContext.current
    var iconBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    
    LaunchedEffect(app.packageName) {
        withContext(Dispatchers.IO) {
            try { iconBitmap = context.packageManager.getApplicationIcon(app.packageName).toBitmap().asImageBitmap() } catch (_: Exception) {}
        }
    }

    val animatedColor by animateColorAsState(targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), animationSpec = spring(stiffness = Spring.StiffnessLow), label = "")

    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).toggleable(value = isSelected, onValueChange = { onToggle() }),
        shape = RoundedCornerShape(20.dp), color = animatedColor
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (iconBitmap != null) Image(bitmap = iconBitmap!!, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
            else Box(modifier = Modifier.size(44.dp).background(Color.Gray, RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Checkbox(checked = isSelected, onCheckedChange = null)
        }
    }
}
