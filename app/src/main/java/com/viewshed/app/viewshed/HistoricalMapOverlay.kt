package com.viewshed.app.viewshed

/**
 * Historical Map Overlays for metal detector / relic hunting.
 * Supports old USGS topo, Sanborn maps, historical imagery with transparency.
 */
object HistoricalMapOverlay {

    data class HistoricalMap(
        val name: String,
        val bounds: com.google.android.gms.maps.model.LatLngBounds,
        val imagePath: String, // local file or asset
        val year: Int
    )

    fun loadHistoricalMap(name: String, bounds: com.google.android.gms.maps.model.LatLngBounds, imagePath: String, year: Int): HistoricalMap {
        return HistoricalMap(name, bounds, imagePath, year)
    }

    // Add to GoogleMap as GroundOverlay with transparency control
    fun addToMap(map: com.google.android.gms.maps.GoogleMap, historicalMap: HistoricalMap, transparency: Float = 0.4f) {
        // TODO: Load bitmap and create GroundOverlay
        // map.addGroundOverlay(...)
    }
}
