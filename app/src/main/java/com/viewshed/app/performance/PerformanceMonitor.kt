package com.viewshed.app.performance

import android.os.SystemClock

/**
 * Phase 8 / Live dashboard — lightweight timing for analysis runs.
 */
object PerformanceMonitor {
    data class Sample(
        val label: String,
        val durationMs: Long,
        val rayCount: Int = 0,
        val sampleCount: Int = 0
    )

    private val recent = ArrayDeque<Sample>(16)

    fun record(label: String, durationMs: Long, rayCount: Int = 0, sampleCount: Int = 0) {
        if (recent.size >= 16) recent.removeFirst()
        recent.addLast(Sample(label, durationMs, rayCount, sampleCount))
    }

    fun last(): Sample? = recent.lastOrNull()

    fun summary(): String {
        val s = last() ?: return "No runs yet"
        return "${s.label}: ${s.durationMs} ms · rays=${s.rayCount} · samples=${s.sampleCount}"
    }

    inline fun <T> timed(label: String, rayCount: Int = 0, sampleCount: Int = 0, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        val result = block()
        record(label, SystemClock.elapsedRealtime() - t0, rayCount, sampleCount)
        return result
    }
}
