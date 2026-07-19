package com.viewshed.app.viewshed

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.*

object HistoricalMapOverlay {

    data class HistoricalMap(
        val name: String,
        val bounds: LatLngBounds,
        val imagePath: String,
        val year: Int
    )

    fun addHistoricalOverlay(
        map: GoogleMap,
        historicalMap: HistoricalMap,
        transparency: Float = 0.35f
    ): GroundOverlay? {
        // Load bitmap from imagePath (asset or file)
        // For production: use BitmapDescriptorFactory.fromPath or asset
        val bitmapDescriptor = BitmapDescriptorFactory.fromAsset(historicalMap.imagePath)

        val overlay = GroundOverlayOptions()
            .image(bitmapDescriptor)
            .positionFromBounds(historicalMap.bounds)
            .transparency(transparency)

        return map.addGroundOverlay(overlay)
    }
}
