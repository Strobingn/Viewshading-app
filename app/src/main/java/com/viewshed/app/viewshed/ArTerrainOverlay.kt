package com.viewshed.app.viewshed

object ArTerrainOverlay {

    fun launchArViewWithTerrain(
        groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
        disturbances: List<GroundDisturbanceDetector.Disturbance> = emptyList()
    ) {
        // Launch AR activity or composable
        // Overlay detected disturbances + ground points in camera view
        // Highlight depressions in red, mounds in blue
    }
}
