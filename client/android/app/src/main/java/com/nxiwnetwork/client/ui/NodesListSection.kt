package com.nxiwnetwork.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nxiwnetwork.client.SettingsStore
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
fun NodesListSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember(context) { SettingsStore(context) }

    val peer by settingsStore.peer.collectAsStateWithLifecycle("")
    val hashes by settingsStore.vkHashes.collectAsStateWithLifecycle("")
    val secondaryHash by settingsStore.secondaryVkHash.collectAsStateWithLifecycle("")
    val workers by settingsStore.workersPerHash.collectAsStateWithLifecycle(16)
    val protocol by settingsStore.protocol.collectAsStateWithLifecycle("udp")
    val port by settingsStore.listenPort.collectAsStateWithLifecycle(9000)
    val sni by settingsStore.sni.collectAsStateWithLifecycle("")
    val savedServersJson by settingsStore.savedServersJson.collectAsStateWithLifecycle("[]")

    val serverList = remember { mutableStateListOf<NxiwNetworkServer>() }
    var serverToEdit by remember { mutableStateOf<NxiwNetworkServer?>(null) }

    fun saveServers() {
        val array = JSONArray()
        serverList.forEach { server ->
            array.put(
                JSONObject().apply {
                    put("id", server.id)
                    put("name", server.name)
                    put("ip", server.ip.trim())
                    put("password", server.password.trim())
                }
            )
        }
        scope.launch { settingsStore.saveServersList(array.toString()) }
    }

    LaunchedEffect(savedServersJson) {
        serverList.clear()
        try {
            val array = JSONArray(savedServersJson)
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                serverList.add(
                    NxiwNetworkServer(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name"),
                        ip = obj.optString("ip").trim(),
                        password = obj.optString("password").trim()
                    )
                )
            }
        } catch (_: Exception) {
            // Keep the section usable if the stored list was corrupted.
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Ноды",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (serverList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Список нод пуст",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(serverList, key = { it.id }) { server ->
                        val isSelected = peer.trim() == server.ip.trim()
                        NodeListItem(
                            server = server,
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch {
                                    settingsStore.save(
                                        peer = server.ip.trim(),
                                        vkHashes = hashes,
                                        secondaryVkHash = secondaryHash,
                                        workersPerHash = workers,
                                        protocol = protocol,
                                        listenPort = port,
                                        sni = sni
                                    )
                                    settingsStore.saveConnectionPassword(server.password.trim())
                                }
                            },
                            onEdit = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                serverToEdit = server
                            }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                serverToEdit = NxiwNetworkServer(name = "", ip = "", password = "")
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Добавить ноду")
        }
    }

    serverToEdit?.let { editedServer ->
        AddEditServerDialog(
            server = editedServer,
            onDismiss = { serverToEdit = null },
            onSave = { updated ->
                val existingIndex = serverList.indexOfFirst { it.id == updated.id }
                val wasSelected = peer.trim() == editedServer.ip.trim()
                if (existingIndex == -1) {
                    serverList.add(updated)
                } else {
                    serverList[existingIndex] = updated
                }
                saveServers()
                if (wasSelected) {
                    scope.launch {
                        settingsStore.save(
                            peer = updated.ip.trim(),
                            vkHashes = hashes,
                            secondaryVkHash = secondaryHash,
                            workersPerHash = workers,
                            protocol = protocol,
                            listenPort = port,
                            sni = sni
                        )
                        settingsStore.saveConnectionPassword(updated.password.trim())
                    }
                }
                serverToEdit = null
            },
            onDelete = {
                serverList.removeAll { it.id == editedServer.id }
                saveServers()
                if (peer.trim() == editedServer.ip.trim()) {
                    scope.launch {
                        settingsStore.save("", hashes, secondaryHash, workers, protocol, port, sni)
                        settingsStore.saveConnectionPassword("")
                    }
                }
                serverToEdit = null
            }
        )
    }
}

@Composable
private fun NodeListItem(
    server: NxiwNetworkServer,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    val itemInteractionSource = remember { MutableInteractionSource() }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = itemInteractionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(24.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Dns,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = server.ip,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Выбрана",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Изменить ноду",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
