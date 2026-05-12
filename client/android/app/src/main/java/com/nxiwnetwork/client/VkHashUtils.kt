package com.nxiwnetwork.client

private val VK_JOIN_LINK_REGEX = Regex(
    pattern = """(?i)(?:https?://)?(?:[a-z0-9-]+\.)?vk\.(?:ru|com)/call/join/([^/?#\s,;]+)"""
)

fun normalizeVkHashInput(input: String): String {
    val trimmed = input.trim().trim(',', ';')
    return VK_JOIN_LINK_REGEX.find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.trimEnd('/')
        ?: trimmed
}

fun normalizeVkHashList(input: String): String {
    return input
        .split(Regex("[,\\r\\n]+"))
        .flatMap { chunk ->
            val linkHashes = VK_JOIN_LINK_REGEX.findAll(chunk)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.trimEnd('/') }
                .toList()

            linkHashes.ifEmpty {
                chunk
                    .split(Regex("\\s+"))
                    .map { normalizeVkHashInput(it) }
            }
        }
        .filter { it.isNotEmpty() }
        .take(3)
        .joinToString(",")
}

fun normalizeVkHashFieldEdit(input: String): String {
    return if (VK_JOIN_LINK_REGEX.containsMatchIn(input)) normalizeVkHashInput(input) else input
}
