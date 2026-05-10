package com.nxiwnetwork.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object UpdateCheckCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val _isChecking = MutableStateFlow(false)
    private val _availableUpdate = MutableStateFlow<AvailableUpdate?>(null)

    val isChecking = _isChecking.asStateFlow()
    val availableUpdate = _availableUpdate.asStateFlow()

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
                _availableUpdate.value = null
                val skippedTag = settingsStore.getSkippedUpdateTag(normalizedChannel).ifBlank { null }
                val result = runCatching {
                    ReleaseUpdater.checkForUpdate(appContext, normalizedChannel, skippedTag)
                }
                val update = result.getOrNull()
                _availableUpdate.value = update
                result.fold(
                    onSuccess = { found ->
                        settingsStore.saveUpdateRateLimitUntil(0L)
                        settingsStore.saveUpdateCheckStatus(found?.let { "Доступна версия ${it.tagName}" } ?: "Обновлений нет")
                    },
                    onFailure = { error ->
                        (error as? GitHubRateLimitException)
                            ?.let { settingsStore.saveUpdateRateLimitUntil(it.resetAtMillis ?: fallbackRateLimitUntil()) }
                        settingsStore.saveUpdateStatus(ReleaseUpdater.describeCheckFailure(error))
                    }
                )
            } finally {
                _isChecking.value = false
            }
        }
    }

    fun clearAvailableUpdate() {
        _availableUpdate.value = null
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
