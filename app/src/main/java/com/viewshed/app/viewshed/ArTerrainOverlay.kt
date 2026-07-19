package com.viewshed.app.viewshed

/**
 * AR integrated with LiDAR data.
 * Shows ground-classified points and detected disturbances live in camera.
 */
object ArTerrainOverlay {

    /**
     * Launch AR view with full LiDAR integration.
     */
    fun launchArWithLidar(
        groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
        disturbances: List<GroundDisturbanceDetector.Disturbance> = emptyList(),
        viewshedResult: ViewshedEngine.ViewshedResult? = null
    ) {
        // Filter to visible ground points if viewshed is available
        val visibleGround = if (viewshedResult != null) {
            LidarPointCloudRenderer.filterVisiblePoints(groundPoints, viewshedResult)
        } else groundPoints

        // TODO: Pass visibleGround + disturbances to AR rendering layer
        // Use ARCore or CameraX + overlay composable
        // Highlight:
        // - Ground points in neutral color
        // - Depressions in red
        // - Mounds in blue/green
        // - Viewshed visible areas with outline
    }

    /**
     * Simpler entry point using already processed data.
     */
    fun launchArViewWithTerrain(
        groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
        disturbances: List<GroundDisturbanceDetector.Disturbance> = emptyList()
    ) {
        launchArWithLidar(groundPoints, disturbances)
    }
}
