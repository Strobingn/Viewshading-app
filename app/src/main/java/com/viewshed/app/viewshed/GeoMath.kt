package com.viewshed.app.viewshed

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    const val EARTH_RADIUS_M = 6_371_000.0

    fun destination(start: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val d = distanceM / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(start.lat)
        val lon1 = Math.toRadians(start.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(d) * cos(lat1),
            cos(d) - sin(lat1) * sin(lat2)
        )
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /** Great-circle distance in meters. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Elevation angle (radians) from eye at [observerElev] to terrain at [targetElev],
     * distance [distM]. Optional curvature + refraction (effective earth radius).
     */
    fun elevationAngleRad(
        observerElev: Double,
        targetElev: Double,
        distM: Double,
        useCurvature: Boolean,
        refractionCoeff: Double
    ): Double {
        if (distM <= 0.0) return 0.0
        val deltaH = if (useCurvature) {
            val curvatureDrop = (distM * distM) / (2.0 * EARTH_RADIUS_M)
            val refraction = refractionCoeff * curvatureDrop
            (targetElev - observerElev) - curvatureDrop + refraction
        } else {
            targetElev - observerElev
        }
        return atan2(deltaH, distM)
    }

    /**
     * Approximate polygon area in m² using spherical excess (rings of lat/lon).
     * Ring may be open or closed.
     */
    fun polygonAreaM2(ring: List<GeoPoint>): Double {
        if (ring.size < 3) return 0.0
        val pts = if (ring.first() == ring.last()) ring.dropLast(1) else ring
        if (pts.size < 3) return 0.0

        var area = 0.0
        val n = pts.size
        for (i in 0 until n) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % n]
            val lat1 = Math.toRadians(p1.lat)
            val lat2 = Math.toRadians(p2.lat)
            val lon1 = Math.toRadians(p1.lon)
            val lon2 = Math.toRadians(p2.lon)
            area += (lon2 - lon1) * (2 + sin(lat1) + sin(lat2))
        }
        area = abs(area) * EARTH_RADIUS_M * EARTH_RADIUS_M / 2.0
        return area
    }

    fun polygonAreaKm2(ring: List<GeoPoint>): Double = polygonAreaM2(ring) / 1_000_000.0

    fun clampBearing(deg: Double): Double {
        var b = deg % 360.0
        if (b < 0) b += 360.0
        return b
    }

    fun degToRad(d: Double): Double = d * PI / 180.0
}
