package com.viewshed.app.viewshed

import kotlin.math.cos
import kotlin.math.sin

/** Deterministic synthetic hills around Newburgh / Hudson valley (offline demo). */
object DemoTerrain {
    fun elevation(point: GeoPoint): Double {
        val lat = point.lat
        val lon = point.lon
        val base = 20.0
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val hill1 = 80 * sin(latRad * 50) * cos(lonRad * 40)
        val hill2 = 40 * sin(latRad * 120 + 1.3) * cos(lonRad * 90)
        val hill3 = 25 * sin(latRad * 300) * cos(lonRad * 250)
        val riverEffect = if (lon < -73.95) -15.0 else 0.0
        val noise = coordHash(lat, lon) * 5
        return base + hill1 + hill2 + hill3 + riverEffect + noise
    }

    private fun coordHash(lat: Double, lon: Double): Double {
        val h = (lat * 100_000 + lon * 100_000).toLong()
        return (h % 100) / 100.0
    }
}
