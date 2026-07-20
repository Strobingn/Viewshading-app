package com.viewshed.app.data

/**
 * Data source support for USGS 3DEP, NASA SRTM, NOAA bathymetry.
 * Skeletons ready for full implementation.
 */
object DemDataSources {
    suspend fun downloadUSGS3DEP(lat: Double, lon: Double, radiusMeters: Double): String? {
        // TODO: Call USGS 3DEP API or EarthExplorer
        return null
    }

    suspend fun downloadNASASRTM(lat: Double, lon: Double): String? {
        // TODO: NASA SRTM endpoint
        return null
    }

    suspend fun downloadNOAABathymetry(lat: Double, lon: Double): String? {
        // Coastal bathymetry
        return null
    }
}
