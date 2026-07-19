package com.viewshed.app.viewshed

/**
 * LiDAR Point Classifier
 * Supports:
 * - Using existing Classification field from LAS files (class 2 = Ground)
 * - Simple progressive ground filter as fallback
 */
object LidarClassifier {

    const val GROUND = 2
    const val LOW_VEG = 3
    const val MED_VEG = 4
    const val HIGH_VEG = 5
    const val BUILDING = 6

    /**
     * Filter to ground points only.
     * If points have classification, use it. Otherwise run simple filter.
     */
    fun filterGroundPoints(points: List<LidarPointCloudRenderer.LidarPoint>): List<LidarPointCloudRenderer.LidarPoint> {
        // If any point has classification info (we can extend LidarPoint later)
        // For now assume we want lowest points + slope check
        return simpleGroundFilter(points)
    }

    private fun simpleGroundFilter(points: List<LidarPointCloudRenderer.LidarPoint>): List<LidarPointCloudRenderer.LidarPoint> {
        if (points.isEmpty()) return emptyList()

        // Very basic progressive lowest point filter
        val sorted = points.sortedBy { it.elevation }
        val ground = mutableListOf<LidarPointCloudRenderer.LidarPoint>()
        var lastElev = sorted.first().elevation

        for (p in sorted) {
            val slope = if (lastElev != 0.0) (p.elevation - lastElev) / 10.0 else 0.0 // rough
            if (p.elevation <= lastElev + 0.5 || slope < 0.3) { // loose ground criteria
                ground.add(p)
                lastElev = p.elevation
            }
        }
        return ground
    }

    /**
     * Check if point is classified as ground (when we have classification data)
     */
    fun isGround(point: LidarPointCloudRenderer.LidarPoint, classification: Int?): Boolean {
        return classification == GROUND
    }
}
