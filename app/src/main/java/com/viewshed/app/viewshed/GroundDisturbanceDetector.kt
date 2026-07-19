package com.viewshed.app.viewshed

/**
 * Quick Ground Disturbance Detector (#8).
 * Automatically highlights areas with significant elevation changes
 * (old foundations, cellars, privies, mounds, etc.)
 */
object GroundDisturbanceDetector {

    data class Disturbance(
        val center: GeoPoint,
        val type: String, // "Depression", "Mound", "Linear"
        val severity: Float
    )

    fun detectDisturbances(
        groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
        thresholdMeters: Double = 0.8
    ): List<Disturbance> {
        if (groundPoints.size < 50) return emptyList()

        val disturbances = mutableListOf<Disturbance>()

        // Simple clustering + elevation deviation detection
        val avgElev = groundPoints.map { it.elevation }.average()

        for (p in groundPoints) {
            val deviation = kotlin.math.abs(p.elevation - avgElev)
            if (deviation > thresholdMeters) {
                val type = if (p.elevation < avgElev) "Depression" else "Mound"
                disturbances.add(Disturbance(GeoPoint(p.lat, p.lon), type, deviation.toFloat()))
            }
        }

        return disturbances
    }
}
