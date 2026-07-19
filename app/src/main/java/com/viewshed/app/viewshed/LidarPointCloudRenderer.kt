package com.viewshed.app.viewshed

/**
 * LiDAR Point Cloud Rendering with proper filtering.
 * Filters points to only those visible according to current viewshed.
 */
object LidarPointCloudRenderer {

    data class LidarPoint(
        val lat: Double,
        val lon: Double,
        val elevation: Double,
        val intensity: Float = 0f
    )

    /**
     * Filters LiDAR points to only those visible from the observer
     * based on the current viewshed result.
     */
    fun filterVisiblePoints(
        allPoints: List<LidarPoint>,
        viewshedResult: ViewshedEngine.ViewshedResult,
        maxDistanceM: Double = 5000.0
    ): List<LidarPoint> {
        if (viewshedResult.visiblePoints.isEmpty()) return emptyList()

        return allPoints.filter { lidarPoint ->
            val lidarGeo = GeoPoint(lidarPoint.lat, lidarPoint.lon)

            // Check if point is within max distance
            val distance = GeoMath.distanceMeters(viewshedResult.observer, lidarGeo)
            if (distance > maxDistanceM) return@filter false

            // Check against visible points (simple proximity to visible horizon)
            viewshedResult.visiblePoints.any { visible ->
                GeoMath.distanceMeters(lidarGeo, visible) < 80 // ~80m tolerance
            }
        }
    }

    fun renderWithVulkan(
        points: List<LidarPoint>,
        viewshedResult: ViewshedEngine.ViewshedResult? = null
    ) {
        val pointsToRender = if (viewshedResult != null) {
            filterVisiblePoints(points, viewshedResult)
        } else {
            points
        }

        // TODO: Actual Vulkan point rendering with pointsToRender
        // Color by elevation using colorByElevation()
    }

    fun colorByElevation(elev: Double, minElev: Double, maxElev: Double): Int {
        val t = ((elev - minElev) / (maxElev - minElev)).coerceIn(0.0, 1.0).toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(240f - t * 240f, 1f, 1f))
    }
}
