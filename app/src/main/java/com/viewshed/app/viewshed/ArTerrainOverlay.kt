package com.viewshed.app.viewshed

/**
 * AR Camera Overlay with terrain highlights (#5).
 * Shows ground features (foundations, depressions) live through the camera.
 */
object ArTerrainOverlay {

    fun createTerrainOverlay(
        groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
        viewshedResult: ViewshedEngine.ViewshedResult? = null
    ): Any {
        // Filter to visible + ground points
        val visibleGround = if (viewshedResult != null) {
            LidarPointCloudRenderer.filterVisiblePoints(groundPoints, viewshedResult)
        } else groundPoints

        // TODO: Render as AR overlay using CameraX + Compose or ARCore
        // Highlight depressions and mounds in different colors
        return visibleGround
    }
}
