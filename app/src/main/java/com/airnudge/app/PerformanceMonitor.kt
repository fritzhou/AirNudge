package com.airnudge.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

data class PerformanceSnapshot(
    val cpuPercent: Double = 0.0,
    val usedMemoryMb: Long = 0L,
    val batteryCurrentMa: Long? = null,
    val batteryTemperatureC: Double? = null,
    val thermalStatus: String = "Unavailable",
    val thermalSevere: Boolean = false
)

class PerformanceMonitor(private val context: Context) {
    private var lastSampleAtMs = 0L
    private var lastCpuTimeMs = 0L

    var snapshot = PerformanceSnapshot()
        private set

    fun sampleIfDue(nowMs: Long = SystemClock.elapsedRealtime()): PerformanceSnapshot {
        if (lastSampleAtMs > 0L && nowMs - lastSampleAtMs < SAMPLE_INTERVAL_MS) return snapshot

        val cpuTimeMs = Process.getElapsedCpuTime()
        val elapsedMs = if (lastSampleAtMs == 0L) 0L else nowMs - lastSampleAtMs
        val cpuPercent = if (elapsedMs > 0L) {
            (cpuTimeMs - lastCpuTimeMs) * 100.0 / elapsedMs
        } else {
            0.0
        }
        lastSampleAtMs = nowMs
        lastCpuTimeMs = cpuTimeMs

        val runtime = Runtime.getRuntime()
        val memoryMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val currentMicroamps = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeUnless { it == Long.MIN_VALUE }
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperatureTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }

        val powerManager = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            -1
        }
        snapshot = PerformanceSnapshot(
            cpuPercent = cpuPercent.coerceAtLeast(0.0),
            usedMemoryMb = memoryMb,
            batteryCurrentMa = currentMicroamps?.div(1_000),
            batteryTemperatureC = temperatureTenths?.div(10.0),
            thermalStatus = thermalLabel(thermal),
            thermalSevere = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                thermal >= PowerManager.THERMAL_STATUS_SEVERE
        )
        return snapshot
    }

    private fun thermalLabel(status: Int): String = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        "Unavailable"
    } else {
        when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "None"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val BYTES_PER_MB = 1_048_576L
    }
}
