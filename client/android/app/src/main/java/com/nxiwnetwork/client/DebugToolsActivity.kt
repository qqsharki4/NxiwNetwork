package com.nxiwnetwork.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nxiwnetwork.client.ui.DebugMenuDialog

class DebugToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppDiagnostics.start(this)

        val settingsStore = SettingsStore(this)
        val appVersionName = readDebugAppVersionName(this)

        setContent {
            val themeMode = settingsStore.themeMode.collectAsStateWithLifecycle("system").value
            val dynamicColor = settingsStore.useDynamicColor.collectAsStateWithLifecycle(true).value
            NxiwTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                DebugMenuDialog(appVersionName = appVersionName) { finish() }
            }
        }
    }
}

private fun readDebugAppVersionName(context: Context): String {
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
        packageInfo.versionName ?: "неизвестна"
    } catch (_: Exception) {
        "неизвестна"
    }
}
