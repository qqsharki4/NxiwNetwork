package com.nxiwnetwork.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NxiwNetworkServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val password: String
)

data class SavedServersDecodeResult(
    val servers: List<NxiwNetworkServer>,
    val normalized: Boolean
)

object StoredServerResolver {
    fun encode(servers: List<NxiwNetworkServer>): String {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(
                JSONObject().apply {
                    put("id", server.id)
                    put("name", server.name)
                    put("ip", server.ip.trim())
                    put("password", server.password.trim())
                }
            )
        }
        return array.toString()
    }

    fun decode(rawJson: String): SavedServersDecodeResult {
        return runCatching {
            val array = JSONArray(rawJson)
            val usedIds = mutableSetOf<String>()
            val servers = mutableListOf<NxiwNetworkServer>()
            var normalized = false

            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                val rawId = obj.optString("id", "").trim()
                val id = if (rawId.isBlank() || rawId in usedIds) {
                    normalized = true
                    UUID.randomUUID().toString()
                } else {
                    rawId
                }
                usedIds += id
                servers += NxiwNetworkServer(
                    id = id,
                    name = obj.optString("name"),
                    ip = obj.optString("ip").trim(),
                    password = obj.optString("password").trim()
                )
            }

            SavedServersDecodeResult(servers, normalized)
        }.getOrElse {
            SavedServersDecodeResult(emptyList(), false)
        }
    }

    fun matchesConfig(server: NxiwNetworkServer, peer: String, password: String): Boolean {
        return peer.trim().isNotBlank() &&
            normalizeNodeEndpoint(server.ip) == normalizeNodeEndpoint(peer.trim()) &&
            server.password.trim() == password.trim()
    }

    fun matchesEndpoint(server: NxiwNetworkServer, peer: String): Boolean {
        return peer.trim().isNotBlank() &&
            normalizeNodeEndpoint(server.ip) == normalizeNodeEndpoint(peer.trim())
    }

    fun findSelectedServer(
        servers: List<NxiwNetworkServer>,
        peer: String,
        password: String,
        selectedServerId: String
    ): NxiwNetworkServer? {
        return servers.firstOrNull { it.id == selectedServerId && matchesConfig(it, peer, password) }
            ?: servers.firstOrNull { matchesConfig(it, peer, password) }
            ?: servers.firstOrNull { matchesEndpoint(it, peer) }
    }

    fun findRuntimeServer(
        servers: List<NxiwNetworkServer>,
        activeTunnelNode: ActiveTunnelNode
    ): NxiwNetworkServer? {
        return servers.firstOrNull { it.id == activeTunnelNode.serverId }
            ?: servers.firstOrNull { matchesConfig(it, activeTunnelNode.peer, activeTunnelNode.connectionPassword) }
            ?: servers.firstOrNull { matchesEndpoint(it, activeTunnelNode.peer) }
    }

    fun findDisplayServer(
        servers: List<NxiwNetworkServer>,
        tunnelRunning: Boolean,
        peer: String,
        password: String,
        selectedServerId: String,
        activeTunnelNode: ActiveTunnelNode
    ): NxiwNetworkServer? {
        return if (tunnelRunning) {
            findRuntimeServer(servers, activeTunnelNode)
        } else {
            findSelectedServer(
                servers = servers,
                peer = peer,
                password = password,
                selectedServerId = selectedServerId
            )
        }
    }
}

fun encodeSavedServers(servers: List<NxiwNetworkServer>): String = StoredServerResolver.encode(servers)

fun decodeSavedServersJson(rawJson: String): SavedServersDecodeResult = StoredServerResolver.decode(rawJson)

fun nodeMatchesActiveConfig(server: NxiwNetworkServer, peer: String, password: String): Boolean =
    StoredServerResolver.matchesConfig(server, peer, password)

fun nodeMatchesActiveEndpoint(server: NxiwNetworkServer, peer: String): Boolean =
    StoredServerResolver.matchesEndpoint(server, peer)
