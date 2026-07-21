package com.viewshed.app.performance

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.viewshed.app.viewshed.SampleQuality
import com.viewshed.app.viewshed.ViewshedParams

enum class ComputeThermalLevel(val label: String) {
    NOMINAL("nominal"),
    LIGHT("light"),
    MODERATE("moderate"),
    SEVERE("severe"),
    CRITICAL("critical"),
}

data class ComputeHealth(
    val thermal: ComputeThermalLevel,
    val batteryPercent: Int,
    val charging: Boolean,
    val powerSave: Boolean,
    val lowMemory: Boolean,
    val memoryClassMb: Int,
    val processors: Int,
)

data class AdaptiveComputeDecision(
    val params: ViewshedParams,
    val reason: String,
    val health: ComputeHealth,
) {
    val adjusted: Boolean get() = reason.isNotEmpty()
}

/** Device-health sampling and deterministic quality caps for long terrain calculations. */
class AdaptiveComputeController(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val activity = context.getSystemService(ActivityManager::class.java)
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    fun start(onChanged: (ComputeHealth) -> Unit) {
        if (Build.VERSION.SDK_INT < 29 || thermalListener != null) return
        val listener = PowerManager.OnThermalStatusChangedListener { onChanged(snapshot()) }
        thermalListener = listener
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let(power::removeThermalStatusListener)
        }
        thermalListener = null
    }

    fun snapshot(): ComputeHealth {
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_LIGHT -> ComputeThermalLevel.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ComputeThermalLevel.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ComputeThermalLevel.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ComputeThermalLevel.CRITICAL
                else -> ComputeThermalLevel.NOMINAL
            }
        } else ComputeThermalLevel.NOMINAL
        return ComputeHealth(
            thermal = thermal,
            batteryPercent = percent,
            charging = charging,
            powerSave = power.isPowerSaveMode,
            lowMemory = memory.lowMemory,
            memoryClassMb = activity.memoryClass,
            processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        )
    }

    fun adapt(params: ViewshedParams): AdaptiveComputeDecision = adapt(params, snapshot())

    companion object {
        fun adapt(params: ViewshedParams, health: ComputeHealth): AdaptiveComputeDecision {
            val raw = params.sanitized()
            val cap = when {
                health.thermal >= ComputeThermalLevel.SEVERE || health.lowMemory -> SampleQuality.LOW
                health.thermal == ComputeThermalLevel.MODERATE || health.powerSave ||
                    (!health.charging && health.batteryPercent in 0..15) -> SampleQuality.MEDIUM
                else -> null
            }
            if (cap == null) return AdaptiveComputeDecision(raw, "", health)
            val adjusted = raw.copy(
                quality = if (raw.quality.ordinal > cap.ordinal) cap else raw.quality,
                numRays = minOf(raw.numRays, cap.rays),
                samplesPerRay = minOf(raw.samplesPerRay, cap.samples),
                parallelRays = health.thermal < ComputeThermalLevel.SEVERE,
            )
            val reason = when {
                health.thermal >= ComputeThermalLevel.SEVERE -> "thermal ${health.thermal.label}"
                health.lowMemory -> "low memory"
                health.powerSave -> "battery saver"
                !health.charging && health.batteryPercent in 0..15 -> "battery ${health.batteryPercent}%"
                else -> "thermal ${health.thermal.label}"
            }
            return AdaptiveComputeDecision(adjusted, reason, health)
        }
    }
}
