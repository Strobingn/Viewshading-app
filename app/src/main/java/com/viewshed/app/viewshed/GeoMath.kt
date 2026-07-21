package com.viewshed.app.viewshed

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    /** Mean Earth radius (WGS84-ish) in meters. */
    const val EARTH_RADIUS_M = 6_371_000.0

    /** Standard atmospheric refraction coefficient (≈ ITU / surveying default). */
    const val DEFAULT_REFRACTION = 0.13

    /** Allowed refraction range used everywhere (params, curvature, backend). */
    const val MIN_REFRACTION = 0.0
    const val MAX_REFRACTION = 0.25

    /** Clamp refraction to the single shared range used by LOS math. */
    fun clampRefraction(refractionCoeff: Double): Double =
        refractionCoeff.coerceIn(MIN_REFRACTION, MAX_REFRACTION)

    /**
     * Destination point given start, bearing (degrees clockwise from north),
     * and great-circle distance in meters.
     */
    fun destination(start: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        if (distanceM <= 0.0) return start
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

    /** Great-circle distance in meters (haversine). */
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
     * Effective Earth radius for LOS with atmospheric refraction.
     * k ≈ 0.13 standard → R_eff = R / (1 − k) > R (less apparent drop).
     */
    fun effectiveEarthRadiusM(refractionCoeff: Double): Double {
        val k = clampRefraction(refractionCoeff)
        return EARTH_RADIUS_M / (1.0 - k)
    }

    /**
     * Geometric horizon distance on a sphere for eye height [eyeHeightM]
     * above a smooth surface: d ≈ √(2 · R_eff · h).
     */
    fun geometricHorizonM(eyeHeightM: Double, refractionCoeff: Double = 0.13): Double {
        val h = eyeHeightM.coerceAtLeast(0.0)
        val r = effectiveEarthRadiusM(refractionCoeff)
        return sqrt(2.0 * r * h)
    }

    /**
     * Elevation angle (radians) from the eye to a target point.
     *
     * Without curvature: α = atan2(z_target − z_eye, dist)
     *
     * With curvature: the target is depressed by d² / (2 R_eff) relative to the
     * tangent plane at the observer (standard radio / surveying approximation):
     *   α = atan2(z_target − z_eye − d²/(2 R_eff), dist)
     *
     * [observerElev] / [targetElev] are absolute elevations (m), e.g. MSL + height AGL.
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
            val rEff = effectiveEarthRadiusM(refractionCoeff)
            val drop = (distM * distM) / (2.0 * rEff)
            (targetElev - observerElev) - drop
        } else {
            targetElev - observerElev
        }
        return atan2(deltaH, distM)
    }

    /**
     * Spherical polygon area (m²) via authalic excess on unit sphere, then scale.
     * Ring may be open or closed. Sign ignored (absolute area).
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
            area += (lon2 - lon1) * (2.0 + sin(lat1) + sin(lat2))
        }
        return abs(area) * EARTH_RADIUS_M * EARTH_RADIUS_M / 2.0
    }

    fun polygonAreaKm2(ring: List<GeoPoint>): Double = polygonAreaM2(ring) / 1_000_000.0

    fun clampBearing(deg: Double): Double {
        var b = deg % 360.0
        if (b < 0) b += 360.0
        return b
    }

    /** Initial bearing degrees clockwise from north (0–360). */
    fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return clampBearing(Math.toDegrees(atan2(y, x)))
    }

    fun degToRad(d: Double): Double = d * PI / 180.0
    fun radToDeg(r: Double): Double = r * 180.0 / PI
}
