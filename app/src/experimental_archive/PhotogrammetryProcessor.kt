package com.viewshed.app.viewshed

/**
 * Photogrammetry for site mapping (exploration for metal detector / relic hunting).
 *
 * Use cases:
 * - Document a hunt site in 3D
 * - Create high-res orthomosaic + DEM from phone photos
 * - Compare with LiDAR data
 * - Record find locations in 3D context
 *
 * Approach:
 * 1. Structured photo capture while walking the site
 * 2. On-device rough reconstruction (ARCore depth) or export for desktop processing
 * 3. Generate point cloud / mesh / orthomosaic
 * 4. Overlay with existing LiDAR hillshade or viewshed
 */
object PhotogrammetryProcessor {

    data class SiteCapture(
        val photos: List<String>,           // file paths
        val center: GeoPoint,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Start a new site capture session.
     */
    fun startSiteCapture(center: GeoPoint): SiteCapture {
        return SiteCapture(emptyList(), center)
    }

    /**
     * Add a geotagged photo to the current capture.
     */
    fun addPhoto(capture: SiteCapture, photoPath: String): SiteCapture {
        return capture.copy(photos = capture.photos + photoPath)
    }

    /**
     * Generate a simple point cloud from captured photos.
     * On-device: Use ARCore depth API for rough reconstruction.
     * Production: Export package for COLMAP / Meshroom / RealityCapture.
     */
    fun generatePointCloud(capture: SiteCapture): List<LidarPointCloudRenderer.LidarPoint> {
        // TODO: Integrate ARCore or call native SfM
        // For now return empty list as placeholder
        return emptyList()
    }

    /**
     * Export capture package ready for desktop photogrammetry.
     */
    fun exportForProcessing(capture: SiteCapture, outputFolder: String) {
        // Create folder with photos + GPS metadata + suggested processing settings
        // User can then run COLMAP/Meshroom locally or in cloud
    }

    /**
     * Compare photogrammetry output with existing LiDAR data.
     */
    fun compareWithLidar(
        photoPoints: List<LidarPointCloudRenderer.LidarPoint>,
        lidarPoints: List<LidarPointCloudRenderer.LidarPoint>
    ): String {
        // Calculate difference / alignment metrics
        return "Comparison ready"
    }
}
