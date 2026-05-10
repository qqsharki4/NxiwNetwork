package com.nxiwnetwork.client

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NxiwTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        
        if (TunnelManager.running.value) {
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
            startService(Intent(this, TunnelService::class.java).apply { action = "STOP" })
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()
            
            scope.launch {
                val store = SettingsStore(applicationContext)
                val peer = store.peer.first()
                val hashes = store.vkHashes.first()
                
                if (peer.isNotBlank() && hashes.isNotBlank()) {
                    val intent = Intent(applicationContext, TunnelService::class.java).apply {
                        action = "START"
                        putExtra("peer", normalizeNodeEndpoint(peer))
                        putExtra("vk_hashes", hashes)
                        putExtra("workers_per_hash", store.workersPerHash.first())
                        putExtra("port", store.listenPort.first())
                        putExtra("protocol", store.protocol.first())
                        putExtra("captcha_mode", store.captchaMode.first())
                        putExtra("connection_password", store.connectionPassword.first())
                        putExtra("wifi_high_performance", store.wifiHighPerformance.first())
                        putExtra("client_keepalive_seconds", store.clientKeepaliveSeconds.first())
                    }
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                }
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRunning = TunnelManager.running.value
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "NxiwNetwork"
        tile.updateTile()
    }
}