package com.nxiwnetwork.client

const val DEFAULT_NODE_PORT = 56000

data class NodeAddress(
    val host: String,
    val port: Int?
) {
    fun toStorageString(): String {
        return port?.let { formatNodeAddress(host, it, includeDefaultPort = true) } ?: host
    }

    fun toEndpointString(defaultPort: Int = DEFAULT_NODE_PORT): String {
        return formatHostPort(host, port ?: defaultPort)
    }
}

fun parseNodeAddress(value: String): NodeAddress? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null

    if (trimmed.startsWith("[")) {
        val end = trimmed.indexOf(']')
        if (end <= 1) return null

        val host = trimmed.substring(1, end).trim()
        val tail = trimmed.substring(end + 1).trim()
        if (host.isBlank()) return null
        if (tail.isBlank()) return NodeAddress(host = host, port = null)
        if (!tail.startsWith(":")) return null

        val port = parseNodePort(tail.removePrefix(":")) ?: return null
        return NodeAddress(host = host, port = port)
    }

    val colonCount = trimmed.count { it == ':' }
    if (colonCount == 0) return NodeAddress(host = trimmed, port = null)

    if (colonCount == 1) {
        val host = trimmed.substringBefore(":").trim()
        val port = parseNodePort(trimmed.substringAfter(":")) ?: return null
        if (host.isBlank()) return null
        return NodeAddress(host = host, port = port)
    }

    // Bare IPv6 without port. IPv6 with port must be written as [addr]:port.
    return NodeAddress(host = trimmed, port = null)
}

fun isValidNodeAddress(value: String): Boolean {
    return parseNodeAddress(value) != null
}

fun normalizeNodeAddressForStorage(value: String): String {
    return parseNodeAddress(value)?.toStorageString() ?: value.trim()
}

fun normalizeNodeEndpoint(value: String, defaultPort: Int = DEFAULT_NODE_PORT): String {
    return parseNodeAddress(value)?.toEndpointString(defaultPort) ?: value.trim()
}

fun nodeEndpointHost(value: String): String {
    return parseNodeAddress(value)?.host.orEmpty()
}

fun nodeEndpointPort(value: String, defaultPort: Int = DEFAULT_NODE_PORT): Int {
    return parseNodeAddress(value)?.port ?: defaultPort
}

fun formatNodeAddress(host: String, port: Int, includeDefaultPort: Boolean = false): String {
    val cleanHost = host.trim().trim('[', ']')
    return if (!includeDefaultPort && port == DEFAULT_NODE_PORT) cleanHost else formatHostPort(cleanHost, port)
}

private fun parseNodePort(value: String): Int? {
    val port = value.trim().toIntOrNull() ?: return null
    return port.takeIf { it in 1..65535 }
}

private fun formatHostPort(host: String, port: Int): String {
    val cleanHost = host.trim().trim('[', ']')
    val formattedHost = if (cleanHost.contains(":")) "[$cleanHost]" else cleanHost
    return "$formattedHost:$port"
}
