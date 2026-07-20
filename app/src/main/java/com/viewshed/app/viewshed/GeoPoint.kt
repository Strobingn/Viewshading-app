package com.viewshed.app.viewshed

import java.util.Locale

/**
 * Pure geographic point for engine + tests (no Android Maps dependency).
 */
data class GeoPoint(val lat: Double, val lon: Double) {
    /** Locale-stable key so elevation lookups match on all devices. */
    fun key(precision: Int = 6): String =
        String.format(Locale.US, "%.${precision}f,%.${precision}f", lat, lon)
}
