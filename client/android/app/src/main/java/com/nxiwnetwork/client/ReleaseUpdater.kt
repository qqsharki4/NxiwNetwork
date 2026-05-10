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
    val changelogAsset: ReleaseAsset?
)

private data class ReleaseCandidate(
    val tagName: String,
    val releaseName: String,
    val releaseUrl: String,
    val version: ReleaseVersion,
    val asset: ReleaseAsset,
    val changelogAsset: ReleaseAsset?
)

class GitHubRateLimitException(val resetAtMillis: Long?) : IOException("GitHub API rate limit")

object ReleaseUpdater {
    private const val RELEASES_URL = "https://api.github.com/repos/qqsharki4/NxiwNetwork/releases"
    private const val MAX_CHANGELOG_BYTES = 256 * 1024
    private val versionRegex = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-?(dev|pre)\.(\d+))?$""", RegexOption.IGNORE_CASE)

    suspend fun checkForUpdate(context: Context, updateChannel: String, skippedTag: String?): AvailableUpdate? = withContext(Dispatchers.IO) {
        val currentVersion = parseVersion(readCurrentVersionName(context)) ?: return@withContext null
        val allowedChannel = parseUpdateChannelId(updateChannel)
        val candidate = fetchReleases()
            .mapNotNull { parseReleaseCandidate(it, allowedChannel) }
            .filter { it.version > currentVersion }
            .filter { it.tagName != skippedTag }
            .maxByOrNull { it.version }
        candidate?.toAvailableUpdate()
    }

    suspend fun downloadUpdateFile(context: Context, update: AvailableUpdate): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
        val outputFile = File(updatesDir, update.asset.name)
        val partialFile = File(updatesDir, "${update.asset.name}.part")

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
            inputStream.use { input ->
                partialFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        if (outputFile.exists()) outputFile.delete()
        if (!partialFile.renameTo(outputFile)) error("Не удалось сохранить APK")
        outputFile
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
            changelogAsset = chooseChangelogAsset(assets, tagName)
        )
    }

    private fun ReleaseCandidate.toAvailableUpdate(): AvailableUpdate {
        val changelogText = changelogAsset
            ?.let { runCatching { fetchTextAsset(it.downloadUrl) }.getOrNull() }
            ?.trim()
            .orEmpty()

        return AvailableUpdate(
            tagName = tagName,
            releaseName = releaseName,
            releaseUrl = releaseUrl,
            body = changelogText.ifBlank { "- К релизу не прикреплен changelog-файл." },
            version = version,
            asset = asset,
            changelogAsset = changelogAsset
        )
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

private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
    return try {
        block()
    } finally {
        disconnect()
    }
}
