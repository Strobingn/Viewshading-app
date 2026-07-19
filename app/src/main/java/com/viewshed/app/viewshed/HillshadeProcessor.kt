package com.viewshed.app.viewshed

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Hillshade with adjustable lighting angles.
 * Azimuth = sun direction (0-360°, 0 = North)
 * Altitude = sun height above horizon (0-90°)
 */
object HillshadeProcessor {

    data class Grid(
        val width: Int,
        val height: Int,
        val minX: Double,
        val minY: Double,
        val cellSize: Double,
        val elevations: DoubleArray
    )

    fun createGridFromPoints(
        points: List<LidarPointCloudRenderer.LidarPoint>,
        cellSizeMeters: Double = 1.0
    ): Grid? {
        if (points.isEmpty()) return null

        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }

        val width = ((maxLon - minLon) * 111000 / cellSizeMeters).toInt().coerceAtLeast(1)
        val height = ((maxLat - minLat) * 111000 / cellSizeMeters).toInt().coerceAtLeast(1)

        val elevations = DoubleArray(width * height) { Double.NaN }

        for (p in points) {
            val col = ((p.lon - minLon) * 111000 / cellSizeMeters).toInt().coerceIn(0, width - 1)
            val row = ((p.lat - minLat) * 111000 / cellSizeMeters).toInt().coerceIn(0, height - 1)
            val idx = row * width + col

            if (elevations[idx].isNaN() || p.elevation < elevations[idx]) {
                elevations[idx] = p.elevation
            }
        }

        return Grid(width, height, minLon, minLat, cellSizeMeters, elevations)
    }

    /**
     * Compute hillshade with adjustable lighting.
     */
    fun computeHillshade(
        grid: Grid,
        azimuth: Double = 315.0,   // Default: NW light
        altitude: Double = 45.0    // Default: 45° sun elevation
    ): Bitmap? {
        val width = grid.width
        val height = grid.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val azimuthRad = Math.toRadians(azimuth)
        val altitudeRad = Math.toRadians(altitude)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val z = grid.elevations[idx]
                if (z.isNaN()) continue

                val dzdx = (grid.elevations[y * width + (x + 1)] - grid.elevations[y * width + (x - 1)]) / (2 * grid.cellSize)
                val dzdy = (grid.elevations[(y + 1) * width + x] - grid.elevations[(y - 1) * width + x]) / (2 * grid.cellSize)

                val slope = Math.atan(Math.sqrt(dzdx * dzdx + dzdy * dzdy))
                val aspect = Math.atan2(dzdy, -dzdx)

                val hillshade = (Math.cos(altitudeRad) * Math.cos(slope) +
                        Math.sin(altitudeRad) * Math.sin(slope) * Math.cos(azimuthRad - aspect))

                val shade = (hillshade * 255).toInt().coerceIn(0, 255)
                bitmap.setPixel(x, y, Color.rgb(shade, shade, shade))
            }
        }

        return bitmap
    }

    /**
     * Full workflow with adjustable lighting angles.
     */
    fun generateHillshadeFromGroundPoints(
        groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
        cellSize: Double = 1.0,
        azimuth: Double = 315.0,
        altitude: Double = 45.0
    ): Bitmap? {
        val grid = createGridFromPoints(groundPoints, cellSize) ?: return null
        return computeHillshade(grid, azimuth, altitude)
    }
}
