package com.viewshed.app.viewshed

object GroundDisturbanceDetector {

    data class Disturbance(
        val center: GeoPoint,
        val type: String,
        val severity: Float
    )

    fun detectDisturbances(
        groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
        thresholdMeters: Double = 0.8
    ): List<Disturbance> {
        if (groundPoints.size < 30) return emptyList()

        val avgElev = groundPoints.map { it.elevation }.average()
        return groundPoints.mapNotNull { p ->
            val deviation = kotlin.math.abs(p.elevation - avgElev)
            if (deviation > thresholdMeters) {
                val type = if (p.elevation < avgElev) "Depression" else "Mound"
                Disturbance(GeoPoint(p.lat, p.lon), type, deviation.toFloat())
            } else null
        }
    }
}
