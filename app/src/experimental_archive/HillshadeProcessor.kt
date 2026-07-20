package com.viewshed.app.viewshed

import android.graphics.Bitmap
import android.graphics.Color

object HillshadeProcessor {

    // ... existing Grid and CPU methods kept for fallback ...

    /**
     * GPU-accelerated hillshade using Vulkan compute.
     * Falls back to CPU if Vulkan is not available.
     */
    fun generateHillshadeGpu(
        groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
        cellSize: Double = 0.5,
        azimuth: Double = 315.0,
        altitude: Double = 35.0
    ): Bitmap? {
        val grid = createGridFromPoints(groundPoints, cellSize) ?: return null

        // TODO: Dispatch to Vulkan compute shader (hillshade_compute.glsl)
        // For now fall back to CPU version with same parameters
        return computeHillshade(grid, azimuth, altitude)
    }

    // Existing CPU hillshade methods remain available
}