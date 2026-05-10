package com.nxiwnetwork.client

enum class UpdateChannel(val priority: Int) {
    DEV(0),
    PRE(1),
    STABLE(2)
}

fun parseUpdateChannel(value: String): UpdateChannel {
    val normalized = value.lowercase()
    return when {
        Regex("""(^|[-.])dev\.\d+""").containsMatchIn(normalized) -> UpdateChannel.DEV
        Regex("""(^|[-.])pre\.\d+""").containsMatchIn(normalized) -> UpdateChannel.PRE
        else -> UpdateChannel.STABLE
    }
}

fun filterChangelogForVersion(markdown: String, versionName: String): String {
    val currentChannel = parseUpdateChannel(versionName)
    val visibleLines = mutableListOf<String>()
    var insideVersionSection = false
    var includeCurrentSection = true

    markdown.lineSequence().forEach { line ->
        if (line.startsWith("## ")) {
            insideVersionSection = true
            val sectionChannel = parseUpdateChannel(line.removePrefix("## ").trim())
            includeCurrentSection = sectionChannel.priority >= currentChannel.priority
        }

        if (!insideVersionSection || includeCurrentSection) {
            visibleLines += line
        }
    }

    return visibleLines.joinToString("\n").ifBlank {
        "# История изменений\n\n- Для текущего канала обновлений пока нет записей."
    }
}
