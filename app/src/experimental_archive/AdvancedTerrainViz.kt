package com.viewshed.app.viewshed

/**
 * Advanced Terrain Visualization for detecting subtle ground features.
 * Includes Sky View Factor and Openness (very effective for old foundations).
 */
object AdvancedTerrainViz {

    fun computeSkyViewFactor(grid: HillshadeProcessor.Grid, radius: Int = 10): android.graphics.Bitmap? {
        // Sky View Factor approximation
        // Lower values = more enclosed (good for detecting depressions/foundations)
        val width = grid.width
        val height = grid.height
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)

        for (y in radius until height - radius) {
            for (x in radius until width - radius) {
                var visibleSky = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (dx*dx + dy*dy > radius*radius) continue
                        val idx = (y + dy) * width + (x + dx)
                        if (grid.elevations[idx] < grid.elevations[y * width + x]) visibleSky++
                    }
                }
                val svf = visibleSky.toFloat() / (radius * radius * 4)
                val shade = (svf * 255).toInt()
                bitmap.setPixel(x, y, android.graphics.Color.rgb(shade, shade, shade))
            }
        }
        return bitmap
    }

    fun computeOpenness(grid: HillshadeProcessor.Grid, radius: Int = 8): android.graphics.Bitmap? {
        // Positive/Negative openness for detecting mounds and depressions
        // Very useful for old foundations and ground disturbance
        return null // TODO: Full implementation
    }
}
