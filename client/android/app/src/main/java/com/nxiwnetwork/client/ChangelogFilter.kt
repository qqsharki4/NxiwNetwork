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

fun filterChangelogForUpdateRange(
    markdown: String,
    previousVersionName: String?,
    currentVersionName: String
): String {
    val currentVersion = ReleaseUpdater.parseVersion(currentVersionName)
    val previousVersion = previousVersionName?.let(ReleaseUpdater::parseVersion)
    val currentChannel = parseUpdateChannel(currentVersionName)
    val visibleLines = mutableListOf<String>()
    var insideVersionSection = false
    var includeCurrentSection = true
    var includedVersionSection = false

    markdown.lineSequence().forEach { line ->
        if (line.startsWith("## ")) {
            insideVersionSection = true
            val sectionTitle = line.removePrefix("## ").trim()
            val sectionVersion = ReleaseUpdater.parseVersion(sectionTitle)
            val sectionChannel = parseUpdateChannel(sectionTitle)
            val matchesChannel = sectionChannel.priority >= currentChannel.priority
            val isNewerThanPrevious = when {
                sectionVersion == null -> previousVersion == null
                previousVersion == null -> sectionVersion == currentVersion
                else -> sectionVersion > previousVersion
            }
            val isNotNewerThanCurrent = currentVersion?.let { sectionVersion == null || sectionVersion <= it } ?: true
            includeCurrentSection = matchesChannel && isNewerThanPrevious && isNotNewerThanCurrent
            if (includeCurrentSection) includedVersionSection = true
        }

        if (!insideVersionSection || includeCurrentSection) {
            visibleLines += line
        }
    }

    return if (includedVersionSection) visibleLines.joinToString("\n") else ""
}
