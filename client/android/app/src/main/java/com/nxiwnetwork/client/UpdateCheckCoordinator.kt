package com.nxiwnetwork.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UpdateDownloadState(
    val tagName: String,
    val progressPercent: Int?,
    val message: String,
    val isActive: Boolean
)

object UpdateCheckCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val _isChecking = MutableStateFlow(false)
    private val _availableUpdates = MutableStateFlow<List<AvailableUpdate>>(emptyList())
    private val _downloadState = MutableStateFlow<UpdateDownloadState?>(null)

    val isChecking = _isChecking.asStateFlow()
    val availableUpdates = _availableUpdates.asStateFlow()
    val downloadState = _downloadState.asStateFlow()

    fun requestManualCheck(
        context: Context,
        settingsStore: SettingsStore,
        channel: String
    ) {
        synchronized(lock) {
            if (_isChecking.value) return
            _isChecking.value = true
        }

        val appContext = context.applicationContext
        val normalizedChannel = normalizeUpdateChannel(channel)
        scope.launch {
            try {
                val result = runCatching {
                    ReleaseUpdater.checkForUpdates(appContext, "dev")
                }
                result.fold(
                    onSuccess = { foundUpdates ->
                        val visibleUpdates = foundUpdates.filter { ReleaseUpdater.updateMatchesChannel(it, normalizedChannel) }
                        _availableUpdates.value = foundUpdates
                        settingsStore.saveCachedAvailableUpdates(foundUpdates)
                        settingsStore.saveUpdateRateLimitUntil(0L)
                        settingsStore.saveUpdateCheckStatus(
                            visibleUpdates.firstOrNull()?.let { "Доступна версия ${it.tagName}" } ?: "Обновлений нет"
                        )
                    },
                    onFailure = { error ->
                        val rateLimit = error as? GitHubRateLimitException
                        if (rateLimit != null) {
                            settingsStore.saveUpdateRateLimitUntil(rateLimit.resetAtMillis ?: fallbackRateLimitUntil())
                        } else {
                            settingsStore.saveUpdateRateLimitUntil(0L)
                        }
                        settingsStore.saveUpdateStatus(ReleaseUpdater.describeCheckFailure(error))
                    }
                )
            } finally {
                _isChecking.value = false
            }
        }
    }

    fun setAvailableUpdates(updates: List<AvailableUpdate>) {
        _availableUpdates.value = updates
    }

    fun clearAvailableUpdates() {
        _availableUpdates.value = emptyList()
    }

    fun setDownloadProgress(tagName: String, progressPercent: Int?, message: String) {
        _downloadState.value = UpdateDownloadState(
            tagName = tagName,
            progressPercent = progressPercent,
            message = message,
            isActive = true
        )
    }

    fun finishDownload(tagName: String, message: String) {
        _downloadState.value = UpdateDownloadState(
            tagName = tagName,
            progressPercent = 100,
            message = message,
            isActive = false
        )
    }

    fun clearDownloadState() {
        _downloadState.value = null
    }

    private fun normalizeUpdateChannel(channel: String): String {
        return when (channel.lowercase()) {
            "pre", "dev" -> channel.lowercase()
            else -> "stable"
        }
    }

    private fun fallbackRateLimitUntil(): Long {
        return System.currentTimeMillis() + 60L * 60L * 1000L
    }
}
