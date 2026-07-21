package com.viewshed.app.performance

import com.viewshed.app.viewshed.SampleQuality
import com.viewshed.app.viewshed.ViewshedParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveComputeControllerTest {
    private val high = ViewshedParams(
        quality = SampleQuality.HIGH,
        numRays = 120,
        samplesPerRay = 120,
        parallelRays = true,
    )

    @Test
    fun severeThermalStateCapsWorkAndDisablesParallelRays() {
        val decision = AdaptiveComputeController.adapt(
            high,
            health(thermal = ComputeThermalLevel.SEVERE),
        )
        assertEquals(SampleQuality.LOW.rays, decision.params.numRays)
        assertEquals(SampleQuality.LOW.samples, decision.params.samplesPerRay)
        assertFalse(decision.params.parallelRays)
        assertTrue(decision.adjusted)
    }

    @Test
    fun healthyDevicePreservesRequestedQuality() {
        val decision = AdaptiveComputeController.adapt(high, health())
        assertEquals(high.sanitized(), decision.params)
        assertFalse(decision.adjusted)
    }

    @Test
    fun powerSaverCapsHighWorkAtBalanced() {
        val decision = AdaptiveComputeController.adapt(high, health(powerSave = true))
        assertEquals(SampleQuality.MEDIUM.rays, decision.params.numRays)
        assertEquals(SampleQuality.MEDIUM.samples, decision.params.samplesPerRay)
        assertTrue(decision.params.parallelRays)
    }

    private fun health(
        thermal: ComputeThermalLevel = ComputeThermalLevel.NOMINAL,
        powerSave: Boolean = false,
    ) = ComputeHealth(
        thermal = thermal,
        batteryPercent = 80,
        charging = false,
        powerSave = powerSave,
        lowMemory = false,
        memoryClassMb = 256,
        processors = 8,
    )
}
