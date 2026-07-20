package com.viewshed.app.viewshed

object LidarPointCloudRenderer {

    data class LidarPoint(
        val lat: Double,
        val lon: Double,
        val elevation: Double,
        val intensity: Float = 0f
    )

    fun loadAndDownsample(filePath: String, center: GeoPoint, zoom: Float): List<LidarPoint> {
        // TODO: Real LAS/LAZ loader
        return emptyList()
    }

    /**
     * Render with optional ground-only filtering
     */
    fun renderWithVulkan(
        points: List<LidarPoint>,
        viewshedResult: ViewshedEngine.ViewshedResult? = null,
        groundOnly: Boolean = false
    ) {
        var pointsToRender = points

        if (groundOnly) {
            pointsToRender = LidarClassifier.filterGroundPoints(points)
        }

        if (viewshedResult != null) {
            pointsToRender = filterVisiblePoints(pointsToRender, viewshedResult)
        }

        // TODO: Vulkan rendering
    }

    private fun filterVisiblePoints(points: List<LidarPoint>, viewshed: ViewshedEngine.ViewshedResult): List<LidarPoint> {
        return points.filter { p ->
            val gp = GeoPoint(p.lat, p.lon)
            viewshed.visiblePoints.any { v -> GeoMath.distanceMeters(gp, v) < 80 }
        }
    }
}
