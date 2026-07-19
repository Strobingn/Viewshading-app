package com.viewshed.app.viewshed

/**
 * Pure geographic point for engine + tests (no Android Maps dependency).
 */
data class GeoPoint(val lat: Double, val lon: Double) {
    fun key(precision: Int = 6): String =
        "${"%.${precision}f".format(lat)},${"%.${precision}f".format(lon)}"
}
