package com.viewshed.app.viewshed

/**
 * Automatically downloads DEM for current map view.
 */
object AutoDemDownloader {
    suspend fun downloadForCurrentView(
        centerLat: Double,
        centerLon: Double,
        zoomLevel: Float
    ): String? {
        val radius = when {
            zoomLevel > 15 -> 500.0
            zoomLevel > 12 -> 2000.0
            else -> 10000.0
        }
        // Try USGS 3DEP first, fallback to SRTM
        return DemDataSources.downloadUSGS3DEP(centerLat, centerLon, radius)
            ?: DemDataSources.downloadNASASRTM(centerLat, centerLon)
    }
}
