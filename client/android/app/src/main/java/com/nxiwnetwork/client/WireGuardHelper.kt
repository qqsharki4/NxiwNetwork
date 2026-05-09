package com.nxiwnetwork.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class WireGuardHelper(context: Context) {
    private val appContext = context.applicationContext
    private val backend = (appContext as NxiwApplication).getBackend(context)

    private companion object {
        val wgMutex = Mutex()
        var sharedTunnel: WgTunnel? = null
    }

    class WgTunnel : Tunnel {
        override fun getName() = "nxiw"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    suspend fun startTunnel(configString: String) = wgMutex.withLock {
        startTunnelLocked(configString)
    }

    private suspend fun startTunnelLocked(configString: String) = withContext(Dispatchers.IO) {
        try {
            if (VpnService.prepare(appContext) != null) {
                throw IllegalStateException("VPN-разрешение не выдано")
            }

            ensureGoBackendServiceStarted()

            sharedTunnel?.let { existingTunnel ->
                try {
                    backend.setState(existingTunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    Log.w("WG", "Failed to stop previous tunnel before restart: ${e.readableMessage()}")
                }
                sharedTunnel = null
                delay(150)
            }

            val parsedConfig = Config.parse(ByteArrayInputStream(configString.toByteArray(Charsets.UTF_8)))
            val settingsStore = SettingsStore(appContext)

            val builder = Interface.Builder()
                .parseAddresses(parsedConfig.`interface`.addresses.joinToString(", ") { it.toString() })

            val selectedDns = settingsStore.customDns.first()
            when {
                selectedDns == "adguard" -> builder.parseDnsServers("94.140.14.14, 94.140.15.15")
                selectedDns == "cloudflare" -> builder.parseDnsServers("1.1.1.1, 1.0.0.1")
                parsedConfig.`interface`.dnsServers.isNotEmpty() ->
                    builder.parseDnsServers(parsedConfig.`interface`.dnsServers.joinToString(", ") { it.hostAddress ?: "" })
            }

            if (parsedConfig.`interface`.listenPort.isPresent) {
                builder.parseListenPort(parsedConfig.`interface`.listenPort.get().toString())
            }

            val userMtu = settingsStore.customMtu.first()
            when {
                userMtu in 1280..1500 -> builder.parseMtu(userMtu.toString())
                parsedConfig.`interface`.mtu.isPresent ->
                    builder.parseMtu(parsedConfig.`interface`.mtu.get().coerceAtLeast(1280).toString())
                else -> builder.parseMtu("1280")
            }

            builder.parsePrivateKey(parsedConfig.`interface`.keyPair.privateKey.toBase64())

            val savedExcluded = settingsStore.excludedApps.first()
            val userSelected = savedExcluded.split(",").filter { it.isNotEmpty() }.toSet()
            val excluded = mutableSetOf(appContext.packageName, "com.vkontakte.android", "com.vk.calls")
            excluded.addAll(userSelected)
            val installedExcluded = excluded.filter { it.isInstalledPackage() }.toSet()
            if (installedExcluded.isNotEmpty()) {
                builder.excludeApplications(installedExcluded)
            }

            val peerBuilder = Peer.Builder()
            val firstPeer = parsedConfig.peers.firstOrNull()
                ?: throw IllegalStateException("WireGuard config has no peer")
            peerBuilder.parsePublicKey(firstPeer.publicKey.toBase64())
            if (firstPeer.preSharedKey.isPresent) peerBuilder.parsePreSharedKey(firstPeer.preSharedKey.get().toBase64())
            if (firstPeer.endpoint.isPresent) peerBuilder.parseEndpoint(firstPeer.endpoint.get().toString())
            if (firstPeer.persistentKeepalive.isPresent) {
                peerBuilder.parsePersistentKeepalive(firstPeer.persistentKeepalive.get().toString())
            }
            peerBuilder.parseAllowedIPs("0.0.0.0/0")

            val finalConfig = Config.Builder()
                .setInterface(builder.build())
                .addPeer(peerBuilder.build())
                .build()

            val nextTunnel = WgTunnel()
            setTunnelUpWithRetry(nextTunnel, finalConfig)
            sharedTunnel = nextTunnel
            Log.d("WG", "WireGuard tunnel started successfully")
        } catch (e: Exception) {
            val detailed = "WireGuard start failed: ${e.readableMessage()}; ${configString.describeWireGuardConfig()}"
            Log.e("WG", detailed)
            e.printStackTrace()
            throw IllegalStateException(detailed, e)
        }
    }

    suspend fun reloadTunnel() = wgMutex.withLock {
        withContext(Dispatchers.IO) {
            val currentTunnel = sharedTunnel ?: return@withContext
            try {
                val configFlow = TunnelManager.config.first() ?: return@withContext
                backend.setState(currentTunnel, Tunnel.State.DOWN, null)
                sharedTunnel = null
                delay(150)
                startTunnelLocked(configFlow)
                Log.d("WG", "WireGuard tunnel reloaded for new settings")
            } catch (e: Exception) {
                Log.e("WG", "Failed to reload WireGuard: ${e.readableMessage()}")
            }
        }
    }

    suspend fun stopTunnel() = wgMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                sharedTunnel?.let {
                    backend.setState(it, Tunnel.State.DOWN, null)
                    sharedTunnel = null
                    Log.d("WG", "WireGuard tunnel stopped")
                }
            } catch (e: Exception) {
                Log.e("WG", "Failed to stop WireGuard: ${e.readableMessage()}")
            }
        }
    }

    private suspend fun ensureGoBackendServiceStarted() {
        withContext(Dispatchers.Main) {
            runCatching {
                appContext.startService(Intent(appContext, GoBackend.VpnService::class.java))
            }.onFailure {
                Log.w("WG", "GoBackend service warmup failed: ${it.readableMessage()}")
            }
        }
        delay(300)
    }

    private suspend fun setTunnelUpWithRetry(nextTunnel: WgTunnel, finalConfig: Config) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                backend.setState(nextTunnel, Tunnel.State.UP, finalConfig)
                return
            } catch (e: Exception) {
                lastError = e
                Log.w("WG", "WireGuard UP attempt ${attempt + 1}/3 failed: ${e.readableMessage()}")
                runCatching { backend.setState(nextTunnel, Tunnel.State.DOWN, null) }
                ensureGoBackendServiceStarted()
                delay(250L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("WireGuard UP failed")
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }

    private fun String.isInstalledPackage(): Boolean {
        return runCatching {
            appContext.packageManager.getPackageInfo(this, 0)
            true
        }.getOrDefault(false)
    }

    private fun String.describeWireGuardConfig(): String {
        val lines = lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val hasInterface = lines.any { it.equals("[Interface]", ignoreCase = true) }
        val hasPeer = lines.any { it.equals("[Peer]", ignoreCase = true) }
        val hasPrivateKey = lines.any { it.startsWith("PrivateKey", ignoreCase = true) }
        val hasPublicKey = lines.any { it.startsWith("PublicKey", ignoreCase = true) }
        val hasAddress = lines.any { it.startsWith("Address", ignoreCase = true) }
        val endpoint = lines.firstOrNull { it.startsWith("Endpoint", ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.trim()
            ?.take(80)
            ?: "none"
        return "config lines=${lines.size}, interface=$hasInterface, peer=$hasPeer, privateKey=$hasPrivateKey, publicKey=$hasPublicKey, address=$hasAddress, endpoint=$endpoint"
    }
}
