package com.nxiwnetwork.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.ceil

data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val channel: UpdateChannel,
    val channelNumber: Int
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)
            .takeIf { it != 0 }
            ?.let { return it }

        compareValues(channel.priority, other.channel.priority)
            .takeIf { it != 0 }
            ?.let { return it }

        return compareValues(channelNumber, other.channelNumber)
    }
}

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val abi: String
)

data class AvailableUpdate(
    val tagName: String,
    val releaseName: String,
    val releaseUrl: String,
    val body: String,
    val version: ReleaseVersion,
    val asset: ReleaseAsset,
    val changelogAsset: ReleaseAsset?,
    val publishedAtMillis: Long
)

private data class ReleaseCandidate(
    val tagName: String,
    val releaseName: String,
    val releaseUrl: String,
    val version: ReleaseVersion,
    val asset: ReleaseAsset,
    val changelogAsset: ReleaseAsset?,
    val publishedAtMillis: Long
)

class GitHubRateLimitException(val resetAtMillis: Long?) : IOException("GitHub API rate limit")

object ReleaseUpdater {
    private const val RELEASES_URL = "https://api.github.com/repos/qqsharki4/NxiwNetwork/releases"
    private const val MAX_CHANGELOG_BYTES = 256 * 1024
    private val versionRegex = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-?(dev|pre)\.(\d+))?$""", RegexOption.IGNORE_CASE)

    suspend fun checkForUpdate(context: Context, updateChannel: String, skippedTag: String?): AvailableUpdate? {
        return checkForUpdates(context, updateChannel).firstOrNull { it.tagName != skippedTag }
    }

    suspend fun checkForUpdates(
        context: Context,
        updateChannel: String
    ): List<AvailableUpdate> = withContext(Dispatchers.IO) {
        val currentVersion = parseVersion(readCurrentVersionName(context)) ?: return@withContext emptyList()
        val allowedChannel = parseUpdateChannelId(updateChannel)
        fetchReleases()
            .mapNotNull { parseReleaseCandidate(it, allowedChannel) }
            .filter { it.version > currentVersion }
            .sortedByDescending { it.version }
            .map { it.toAvailableUpdate() }
    }

    fun encodeAvailableUpdates(updates: List<AvailableUpdate>): String {
        val array = JSONArray()
        updates.forEach { update ->
            array.put(JSONObject().apply {
                put("tagName", update.tagName)
                put("releaseName", update.releaseName)
                put("releaseUrl", update.releaseUrl)
                put("body", update.body)
                put("version", update.version.toJson())
                put("asset", update.asset.toJson())
                put("changelogAsset", update.changelogAsset?.toJson() ?: JSONObject.NULL)
                put("publishedAtMillis", update.publishedAtMillis)
            })
        }
        return array.toString()
    }

    fun decodeAvailableUpdates(value: String): List<AvailableUpdate> {
        if (value.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val version = obj.optJSONObject("version")?.toReleaseVersion() ?: continue
                    val asset = obj.optJSONObject("asset")?.toReleaseAsset() ?: continue
                    add(
                        AvailableUpdate(
                            tagName = obj.optString("tagName"),
                            releaseName = obj.optString("releaseName").ifBlank { obj.optString("tagName") },
                            releaseUrl = obj.optString("releaseUrl"),
                            body = obj.optString("body").ifBlank { "- Нет чейнджлога к этому обновлению." },
                            version = version,
                            asset = asset,
                            changelogAsset = obj.optJSONObject("changelogAsset")?.toReleaseAsset(),
                            publishedAtMillis = obj.optLong("publishedAtMillis", 0L)
                        )
                    )
                }
            }.sortedByDescending { it.version }
        }.getOrDefault(emptyList())
    }

    suspend fun downloadUpdateFile(
        context: Context,
        update: AvailableUpdate,
        onProgress: (Int?) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val outputFile = downloadedUpdateFile(context, update)
        val partialFile = File(outputFile.parentFile, "${update.asset.name}.part")
        if (partialFile.exists()) partialFile.delete()

        val connection = (URL(update.asset.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NxiwNetwork-Android")
        }

        connection.use {
            val code = responseCode
            if (code !in 200..299) error("APK download failed: HTTP $code")
            val totalBytes = contentLengthLong.takeIf { it > 0L }
            var copiedBytes = 0L
            onProgress(totalBytes?.let { 0 })
            inputStream.use { input ->
                partialFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copiedBytes += read
                        totalBytes?.let { total ->
                            onProgress(((copiedBytes * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        }

        if (outputFile.exists()) outputFile.delete()
        if (!partialFile.renameTo(outputFile)) error("Не удалось сохранить APK")
        outputFile
    }

    fun downloadedUpdateFile(context: Context, update: AvailableUpdate): File {
        return File(updatesDir(context), update.asset.name)
    }

    fun isUpdateDownloaded(context: Context, update: AvailableUpdate): Boolean {
        return downloadedUpdateFile(context, update).let { it.isFile && it.length() > 0L }
    }

    private fun updatesDir(context: Context): File {
        return File(context.filesDir, "updates").apply { mkdirs() }
    }

    fun canInstallDownloadedApks(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun installDownloadedApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun parseUpdateChannelId(channel: String): UpdateChannel {
        return when (channel.lowercase()) {
            "dev" -> UpdateChannel.DEV
            "pre" -> UpdateChannel.PRE
            else -> UpdateChannel.STABLE
        }
    }

    fun updateMatchesChannel(update: AvailableUpdate, channel: String): Boolean {
        return update.version.channel.priority >= parseUpdateChannelId(channel).priority
    }

    fun filterNewerThanInstalled(context: Context, updates: List<AvailableUpdate>): List<AvailableUpdate> {
        val currentVersion = parseVersion(readCurrentVersionName(context)) ?: return updates
        return updates
            .filter { it.version > currentVersion }
            .sortedByDescending { it.version }
    }

    fun parseVersion(value: String): ReleaseVersion? {
        val match = versionRegex.matchEntire(value.trim()) ?: return null
        val suffix = match.groupValues.getOrNull(4).orEmpty().lowercase()
        val channel = when (suffix) {
            "dev" -> UpdateChannel.DEV
            "pre" -> UpdateChannel.PRE
            else -> UpdateChannel.STABLE
        }
        return ReleaseVersion(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toInt(),
            patch = match.groupValues[3].toInt(),
            channel = channel,
            channelNumber = match.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
        )
    }

    fun describeCheckFailure(error: Throwable): String {
        return when (error) {
            is GitHubRateLimitException -> {
                val retryAfter = error.resetAtMillis?.let(::formatRetryAfter)
                retryAfter?.let { "GitHub ограничил проверки на $it." }
                    ?: "GitHub временно ограничил проверки. Попробуй позже."
            }
            else -> "Ошибка проверки обновлений"
        }
    }

    private fun parseReleaseCandidate(release: JSONObject, allowedChannel: UpdateChannel): ReleaseCandidate? {
        if (release.optBoolean("draft", false)) return null

        val tagName = release.optString("tag_name").trim()
        val version = parseVersion(tagName) ?: return null
        if (version.channel.priority < allowedChannel.priority) return null

        val assets = release.optJSONArray("assets") ?: JSONArray()
        val asset = chooseBestAsset(assets) ?: return null

        return ReleaseCandidate(
            tagName = tagName,
            releaseName = release.optString("name").ifBlank { tagName },
            releaseUrl = release.optString("html_url"),
            version = version,
            asset = asset,
            changelogAsset = chooseChangelogAsset(assets, tagName),
            publishedAtMillis = parseGitHubTime(release.optString("published_at"))
        )
    }

    private fun ReleaseCandidate.toAvailableUpdate(): AvailableUpdate {
        val changelogText = when (val asset = changelogAsset) {
            null -> "- Нет чейнджлога к этому обновлению."
            else -> runCatching { fetchTextAsset(asset.downloadUrl).trim() }
                .fold(
                    onSuccess = { it.ifBlank { "- Нет чейнджлога к этому обновлению." } },
                    onFailure = { "- Не удалось загрузить чейнджлог этого обновления. Проверь интернет и попробуй позже." }
                )
        }

        return AvailableUpdate(
            tagName = tagName,
            releaseName = releaseName,
            releaseUrl = releaseUrl,
            body = changelogText,
            version = version,
            asset = asset,
            changelogAsset = changelogAsset,
            publishedAtMillis = publishedAtMillis
        )
    }

    private fun parseGitHubTime(value: String): Long {
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    }

    private fun chooseBestAsset(assets: JSONArray): ReleaseAsset? {
        val apkAssets = buildList {
            for (i in 0 until assets.length()) {
                val obj = assets.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                val url = obj.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    add(ReleaseAsset(name = name, downloadUrl = url, abi = detectAssetAbi(name)))
                }
            }
        }

        val preferredAbi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
        return apkAssets.firstOrNull { it.abi == preferredAbi }
            ?: apkAssets.firstOrNull { it.abi == "universal" }
            ?: apkAssets.firstOrNull()
    }

    private fun detectAssetAbi(name: String): String {
        return when {
            name.contains("arm64-v8a", ignoreCase = true) -> "arm64-v8a"
            name.contains("armeabi-v7a", ignoreCase = true) -> "armeabi-v7a"
            name.contains("universal", ignoreCase = true) -> "universal"
            else -> "unknown"
        }
    }

    private fun chooseChangelogAsset(assets: JSONArray, tagName: String): ReleaseAsset? {
        val normalizedTag = tagName.removePrefix("v").lowercase()
        return buildList {
            for (i in 0 until assets.length()) {
                val obj = assets.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                val url = obj.optString("browser_download_url").trim()
                val lowerName = name.lowercase()
                val contentType = obj.optString("content_type").lowercase()
                val isTextFile = lowerName.endsWith(".md") ||
                    lowerName.endsWith(".markdown") ||
                    lowerName.endsWith(".txt") ||
                    contentType.contains("markdown") ||
                    contentType.startsWith("text/plain")
                val isChangelog = lowerName.contains("changelog") || lowerName.contains("release-notes")
                if (isTextFile && isChangelog && url.isNotBlank()) {
                    add(ReleaseAsset(name = name, downloadUrl = url, abi = "changelog"))
                }
            }
        }.maxByOrNull { asset ->
            val lowerName = asset.name.lowercase()
            var score = 0
            if (lowerName.contains(normalizedTag)) score += 8
            if (lowerName == "changelog-$normalizedTag.md") score += 4
            if (lowerName.startsWith("nxiwnetwork")) score += 2
            if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) score += 1
            score
        }
    }

    private fun fetchTextAsset(downloadUrl: String): String {
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NxiwNetwork-Android")
        }

        return connection.use {
            val code = responseCode
            if (code !in 200..299) error("Changelog download failed: HTTP $code")
            inputStream.use { input ->
                val bytes = input.readBytes().take(MAX_CHANGELOG_BYTES).toByteArray()
                bytes.toString(Charsets.UTF_8)
            }
        }
    }

    private fun fetchReleases(): List<JSONObject> {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NxiwNetwork-Android")
        }

        return connection.use {
            val code = responseCode
            val stream = if (code in 200..299) inputStream else errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (isRateLimitResponse(this, code, body)) {
                throw GitHubRateLimitException(readRetryAtMillis(this))
            }
            if (code !in 200..299) error("GitHub releases request failed: HTTP $code")
            val array = JSONArray(body)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let(::add)
                }
            }
        }
    }

    private fun isRateLimitResponse(connection: HttpURLConnection, code: Int, body: String): Boolean {
        if (code != HttpURLConnection.HTTP_FORBIDDEN && code != 429) return false
        val remaining = connection.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
        val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
        val message = body.lowercase()
        return remaining == 0 ||
            retryAfter != null ||
            "rate limit" in message ||
            "secondary rate" in message ||
            "abuse detection" in message
    }

    private fun readRetryAtMillis(connection: HttpURLConnection): Long? {
        val retryAfterSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
        if (retryAfterSeconds != null) return System.currentTimeMillis() + retryAfterSeconds * 1000L

        val resetSeconds = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
        return resetSeconds?.let { it * 1000L }
    }

    private fun formatRetryAfter(resetAtMillis: Long): String {
        val remainingMinutes = ceil(((resetAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)) / 60_000.0)
            .toLong()
            .coerceAtLeast(1L)
        if (remainingMinutes < 60L) {
            return "$remainingMinutes ${pluralRu(remainingMinutes, "минуту", "минуты", "минут")}"
        }

        val hours = remainingMinutes / 60L
        val minutes = remainingMinutes % 60L
        return buildList {
            add("$hours ${pluralRu(hours, "час", "часа", "часов")}")
            if (minutes > 0L) add("$minutes ${pluralRu(minutes, "минуту", "минуты", "минут")}")
        }.joinToString(" ")
    }

    private fun pluralRu(value: Long, one: String, few: String, many: String): String {
        val lastTwoDigits = value % 100L
        if (lastTwoDigits in 11L..14L) return many

        return when (value % 10L) {
            1L -> one
            2L, 3L, 4L -> few
            else -> many
        }
    }

    private fun readCurrentVersionName(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }
}

private fun ReleaseVersion.toJson(): JSONObject {
    return JSONObject().apply {
        put("major", major)
        put("minor", minor)
        put("patch", patch)
        put("channel", channel.name.lowercase())
        put("channelNumber", channelNumber)
    }
}

private fun JSONObject.toReleaseVersion(): ReleaseVersion? {
    val channel = ReleaseUpdater.parseUpdateChannelId(optString("channel", "stable"))
    return ReleaseVersion(
        major = optInt("major", -1).takeIf { it >= 0 } ?: return null,
        minor = optInt("minor", -1).takeIf { it >= 0 } ?: return null,
        patch = optInt("patch", -1).takeIf { it >= 0 } ?: return null,
        channel = channel,
        channelNumber = optInt("channelNumber", 0)
    )
}

private fun ReleaseAsset.toJson(): JSONObject {
    return JSONObject().apply {
        put("name", name)
        put("downloadUrl", downloadUrl)
        put("abi", abi)
    }
}

private fun JSONObject.toReleaseAsset(): ReleaseAsset? {
    val name = optString("name")
    val downloadUrl = optString("downloadUrl")
    if (name.isBlank() || downloadUrl.isBlank()) return null
    return ReleaseAsset(
        name = name,
        downloadUrl = downloadUrl,
        abi = optString("abi", "unknown")
    )
}

private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
    return try {
        block()
    } finally {
        disconnect()
    }
}

fun formatUpdatePublishedAgo(publishedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (publishedAtMillis <= 0L) return "Дата публикации неизвестна"

    val elapsedMillis = (nowMillis - publishedAtMillis).coerceAtLeast(0L)
    if (elapsedMillis < 60_000L) return "Вышло меньше минуты назад"

    val elapsedMinutes = elapsedMillis / 60_000L
    val days = elapsedMinutes / (24L * 60L)
    val hours = (elapsedMinutes % (24L * 60L)) / 60L
    val minutes = elapsedMinutes % 60L
    val parts = buildList {
        if (days > 0L) add("$days ${pluralRuTime(days, "день", "дня", "дней")}")
        if (hours > 0L) add("$hours ${pluralRuTime(hours, "час", "часа", "часов")}")
        if (minutes > 0L) add("$minutes ${pluralRuTime(minutes, "минуту", "минуты", "минут")}")
    }.ifEmpty {
        listOf("1 минуту")
    }

    return "Вышло ${parts.joinToString(" ")} назад"
}

private fun pluralRuTime(value: Long, one: String, few: String, many: String): String {
    val lastTwoDigits = value % 100L
    if (lastTwoDigits in 11L..14L) return many

    return when (value % 10L) {
        1L -> one
        2L, 3L, 4L -> few
        else -> many
    }
}
