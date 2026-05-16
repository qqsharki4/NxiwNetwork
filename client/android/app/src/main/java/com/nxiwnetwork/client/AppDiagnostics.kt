package com.nxiwnetwork.client

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class AppDiagnosticsConfig(
    val enabled: Boolean = false,
    val processMetrics: Boolean = true,
    val trafficMetrics: Boolean = true,
    val batteryMetrics: Boolean = true
)

data class ProcessDiagnostics(
    val pid: Int? = null,
    val cpuPercent: Float? = null,
    val cpuTimeMs: Long? = null,
    val pssKb: Int? = null,
    val privateDirtyKb: Int? = null,
    val nativePssKb: Int? = null,
    val dalvikPssKb: Int? = null,
    val otherPssKb: Int? = null,
    val threadCount: Int? = null
)

data class TrafficDiagnostics(
    val uidRxBytes: Long? = null,
    val uidTxBytes: Long? = null,
    val rxBytesPerSecond: Long? = null,
    val txBytesPerSecond: Long? = null
)

data class BatteryDiagnostics(
    val levelPercent: Int? = null,
    val isCharging: Boolean? = null,
    val plugged: String? = null,
    val temperatureC: Float? = null,
    val voltageMv: Int? = null,
    val currentMa: Int? = null,
    val chargeMah: Int? = null
)

data class StorageDiagnostics(
    val dataTotalBytes: Long = 0L,
    val dataFreeBytes: Long = 0L,
    val dataUsableBytes: Long = 0L
)

data class AppDiagnosticsSnapshot(
    val config: AppDiagnosticsConfig = AppDiagnosticsConfig(),
    val sampledAtElapsedMs: Long = 0L,
    val appProcessUptimeMs: Long = 0L,
    val tunnelUptimeMs: Long? = null,
    val cpuCoreCount: Int = Runtime.getRuntime().availableProcessors(),
    val javaHeapUsedBytes: Long = 0L,
    val javaHeapMaxBytes: Long = 0L,
    val javaHeapFreeBytes: Long = 0L,
    val appProcess: ProcessDiagnostics = ProcessDiagnostics(),
    val coreProcess: ProcessDiagnostics = ProcessDiagnostics(),
    val traffic: TrafficDiagnostics = TrafficDiagnostics(),
    val battery: BatteryDiagnostics = BatteryDiagnostics(),
    val storage: StorageDiagnostics = StorageDiagnostics()
) {
    val enabled: Boolean get() = config.enabled
}

object AppDiagnostics {
    val snapshot = MutableStateFlow(AppDiagnosticsSnapshot())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processStartElapsedMs = SystemClock.elapsedRealtime()
    private var settingsJob: Job? = null
    private var sampleJob: Job? = null
    private val lock = Any()

    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (settingsJob != null) return
            val store = SettingsStore(appContext)
            settingsJob = scope.launch {
                combine(
                    store.diagnosticsEnabled,
                    store.diagnosticsProcessMetrics,
                    store.diagnosticsTrafficMetrics,
                    store.diagnosticsBatteryMetrics
                ) { enabled, processMetrics, trafficMetrics, batteryMetrics ->
                    AppDiagnosticsConfig(
                        enabled = enabled,
                        processMetrics = processMetrics,
                        trafficMetrics = trafficMetrics,
                        batteryMetrics = batteryMetrics
                    )
                }
                    .distinctUntilChanged()
                    .collect { config -> applyConfig(appContext, config) }
            }
        }
    }

    private fun applyConfig(context: Context, config: AppDiagnosticsConfig) {
        sampleJob?.cancel()
        sampleJob = null

        if (!config.enabled) {
            snapshot.value = snapshot.value.copy(
                config = config,
                sampledAtElapsedMs = SystemClock.elapsedRealtime()
            )
            return
        }

        val sampler = DiagnosticsSampler(context.applicationContext, processStartElapsedMs)
        sampleJob = scope.launch {
            while (isActive) {
                snapshot.value = sampler.sample(config)
                delay(1_000L)
            }
        }
    }
}

private class DiagnosticsSampler(
    private val context: Context,
    private val processStartElapsedMs: Long
) {
    private val ticksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrDefault(100L)
        .takeIf { it > 0L } ?: 100L
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val previousProcStats = mutableMapOf<Int, ProcStat>()
    private var previousTraffic: TrafficPoint? = null

    fun sample(config: AppDiagnosticsConfig): AppDiagnosticsSnapshot {
        val now = SystemClock.elapsedRealtime()
        val runtime = Runtime.getRuntime()
        val appPid = android.os.Process.myPid()
        val corePid = TunnelManager.coreProcessPid.value
        val tunnelStartedAt = TunnelManager.tunnelStartedAtElapsedMs.value

        val appProcess = if (config.processMetrics) sampleProcess(appPid) else ProcessDiagnostics(pid = appPid)
        val coreProcess = if (config.processMetrics && corePid != null) {
            sampleProcess(corePid)
        } else {
            ProcessDiagnostics(pid = corePid)
        }

        return AppDiagnosticsSnapshot(
            config = config,
            sampledAtElapsedMs = now,
            appProcessUptimeMs = now - processStartElapsedMs,
            tunnelUptimeMs = tunnelStartedAt?.let { (now - it).coerceAtLeast(0L) },
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            javaHeapMaxBytes = runtime.maxMemory(),
            javaHeapFreeBytes = runtime.freeMemory(),
            appProcess = appProcess,
            coreProcess = coreProcess,
            traffic = if (config.trafficMetrics) sampleTraffic() else TrafficDiagnostics(),
            battery = if (config.batteryMetrics) sampleBattery() else BatteryDiagnostics(),
            storage = sampleStorage()
        )
    }

    private fun sampleProcess(pid: Int): ProcessDiagnostics {
        val stat = readProcStat(pid)
        val previous = previousProcStats[pid]
        if (stat != null) previousProcStats[pid] = stat

        val cpuPercent = if (stat != null && previous != null) {
            val cpuDelta = stat.cpuTimeMs - previous.cpuTimeMs
            val wallDelta = stat.sampledAtElapsedMs - previous.sampledAtElapsedMs
            if (wallDelta > 0L && cpuDelta >= 0L) (cpuDelta.toFloat() / wallDelta.toFloat()) * 100f else null
        } else {
            null
        }

        val memory = readMemory(pid)
        return ProcessDiagnostics(
            pid = pid,
            cpuPercent = cpuPercent,
            cpuTimeMs = stat?.cpuTimeMs,
            pssKb = memory?.totalPss,
            privateDirtyKb = memory?.totalPrivateDirty,
            nativePssKb = memory?.nativePss,
            dalvikPssKb = memory?.dalvikPss,
            otherPssKb = memory?.otherPss,
            threadCount = stat?.threadCount
        )
    }

    private fun readProcStat(pid: Int): ProcStat? {
        return runCatching {
            val text = File("/proc/$pid/stat").readText()
            val endComm = text.lastIndexOf(')')
            if (endComm <= 0 || endComm + 2 >= text.length) return@runCatching null
            val parts = text.substring(endComm + 2).trim().split(Regex("\\s+"))
            val userTicks = parts.getOrNull(11)?.toLongOrNull() ?: return@runCatching null
            val systemTicks = parts.getOrNull(12)?.toLongOrNull() ?: return@runCatching null
            val threads = parts.getOrNull(17)?.toIntOrNull()
            ProcStat(
                pid = pid,
                cpuTimeMs = ((userTicks + systemTicks) * 1000L) / ticksPerSecond,
                threadCount = threads,
                sampledAtElapsedMs = SystemClock.elapsedRealtime()
            )
        }.getOrNull()
    }

    private fun readMemory(pid: Int): android.os.Debug.MemoryInfo? {
        return runCatching {
            activityManager.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()
        }.getOrNull()
    }

    private fun sampleTraffic(): TrafficDiagnostics {
        val uid = android.os.Process.myUid()
        val now = SystemClock.elapsedRealtime()
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        val rx = TrafficStats.getUidRxBytes(uid).takeIf { it != unsupported }
        val tx = TrafficStats.getUidTxBytes(uid).takeIf { it != unsupported }
        val previous = previousTraffic
        previousTraffic = TrafficPoint(now, rx, tx)

        val elapsedMs = previous?.let { now - it.sampledAtElapsedMs } ?: 0L
        val rxRate = bytesPerSecond(rx, previous?.rxBytes, elapsedMs)
        val txRate = bytesPerSecond(tx, previous?.txBytes, elapsedMs)

        return TrafficDiagnostics(
            uidRxBytes = rx,
            uidTxBytes = tx,
            rxBytesPerSecond = rxRate,
            txBytesPerSecond = txRate
        )
    }

    private fun bytesPerSecond(current: Long?, previous: Long?, elapsedMs: Long): Long? {
        if (current == null || previous == null || elapsedMs <= 0L || current < previous) return null
        return ((current - previous) * 1000L) / elapsedMs
    }

    private fun sampleBattery(): BatteryDiagnostics {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE

        val currentMicroA = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Int.MIN_VALUE }
        val chargeMicroAh = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it != Int.MIN_VALUE }

        return BatteryDiagnostics(
            levelPercent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else null,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            plugged = pluggedLabel(plugged),
            temperatureC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else null,
            voltageMv = voltageMv.takeIf { it != Int.MIN_VALUE },
            currentMa = currentMicroA?.let { it / 1000 },
            chargeMah = chargeMicroAh?.let { it / 1000 }
        )
    }

    private fun pluggedLabel(plugged: Int): String {
        return when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> "Battery"
            else -> "Other"
        }
    }

    private fun sampleStorage(): StorageDiagnostics {
        val dataDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.dataDir else context.filesDir.parentFile
        return StorageDiagnostics(
            dataTotalBytes = dataDir?.totalSpace ?: 0L,
            dataFreeBytes = dataDir?.freeSpace ?: 0L,
            dataUsableBytes = dataDir?.usableSpace ?: 0L
        )
    }
}

private data class ProcStat(
    val pid: Int,
    val cpuTimeMs: Long,
    val threadCount: Int?,
    val sampledAtElapsedMs: Long
)

private data class TrafficPoint(
    val sampledAtElapsedMs: Long,
    val rxBytes: Long?,
    val txBytes: Long?
)
