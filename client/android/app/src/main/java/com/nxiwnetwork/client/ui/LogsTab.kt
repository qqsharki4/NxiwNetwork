package com.nxiwnetwork.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nxiwnetwork.client.LogEntry
import com.nxiwnetwork.client.TunnelManager

@Composable
fun LogsTab() {
    val context = LocalContext.current
    val currentLogs by TunnelManager.logs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(currentLogs.size) {
        if (currentLogs.isNotEmpty()) {
            listState.animateScrollToItem(currentLogs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Лог событий", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { TunnelManager.clearLogs() }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                    Icon(Icons.Default.DeleteOutline, null)
                }
                FilledTonalIconButton(onClick = {
                    val text = currentLogs.joinToString("\n") { "${it.message} (x${it.count})" }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NxiwNetwork Log", text))
                    Toast.makeText(context, "Все логи скопированы", Toast.LENGTH_SHORT).show()
                }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                    Icon(Icons.Default.ContentCopy, null)
                }
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(24.dp), tonalElevation = 4.dp) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(currentLogs, key = { it.key }) { entry -> 
                    LogLine(entry, context) 
                }
            }
        }
    }
}

@Composable
fun LogLine(entry: LogEntry, context: Context, modifier: Modifier = Modifier) {
    val color = when {
        entry.isError -> MaterialTheme.colorScheme.error
        entry.priority <= 2 -> MaterialTheme.colorScheme.primary
        entry.priority == 3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    var trigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(entry.count) { trigger++ }
    val animatedScale by animateFloatAsState(targetValue = if (trigger > 0) 1.2f else 1.0f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "", finishedListener = { trigger = 0 })

    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { 
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("NxiwNetwork Log", entry.message))
            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
        }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.graphicsLayer(scaleX = animatedScale, scaleY = animatedScale).background(color = color.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)).defaultMinSize(minWidth = 32.dp, minHeight = 24.dp).padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("${entry.count}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(entry.message, color = color, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = if (entry.isError) FontWeight.Bold else FontWeight.Medium, lineHeight = 20.sp, modifier = Modifier.weight(1f))
    }
}