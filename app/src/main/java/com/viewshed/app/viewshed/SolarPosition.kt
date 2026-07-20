package com.viewshed.app.viewshed

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Solar position (NOAA-style simplified). Used for shadow / sun LOS analysis.
 */
data class SolarPos(
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val zenithDeg: Double
) {
    val isUp: Boolean get() = altitudeDeg > 0.0
}

object SolarPosition {

    fun at(
        lat: Double,
        lon: Double,
        timeMs: Long = System.currentTimeMillis(),
        tz: TimeZone = TimeZone.getDefault()
    ): SolarPos {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = timeMs }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY) +
            cal.get(Calendar.MINUTE) / 60.0 +
            cal.get(Calendar.SECOND) / 3600.0

        val jd = julianDay(year, month, day, hour, tz.getOffset(timeMs) / 3_600_000.0)
        val t = (jd - 2451545.0) / 36525.0

        // Geometric mean longitude / anomaly (deg)
        var L0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360.0
        if (L0 < 0) L0 += 360.0
        val M = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val Mr = Math.toRadians(M)
        val C = sin(Mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * Mr) * (0.019993 - 0.000101 * t) +
            sin(3 * Mr) * 0.000289
        val sunTrue = L0 + C
        val omega = 125.04 - 1934.136 * t
        val lambda = Math.toRadians(sunTrue - 0.00569 - 0.00478 * sin(Math.toRadians(omega)))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = Math.toRadians(eps0 + 0.00256 * cos(Math.toRadians(omega)))

        val decl = asin(sin(eps) * sin(lambda))
        // Equation of time (minutes)
        val y = tan(eps / 2).let { it * it }
        val L0r = Math.toRadians(L0)
        val eqTime = 4 * Math.toDegrees(
            y * sin(2 * L0r) - 2 * e * sin(Mr) + 4 * e * y * sin(Mr) * cos(2 * L0r) -
                0.5 * y * y * sin(4 * L0r) - 1.25 * e * e * sin(2 * Mr)
        )
        val offsetH = tz.getOffset(timeMs) / 3_600_000.0
        val timeOffset = eqTime + 4 * lon - 60 * offsetH
        var trueSolar = hour * 60 + timeOffset
        while (trueSolar < 0) trueSolar += 1440
        while (trueSolar >= 1440) trueSolar -= 1440
        val ha = Math.toRadians(if (trueSolar / 4 < 0) trueSolar / 4 + 180 else trueSolar / 4 - 180)

        val latR = Math.toRadians(lat)
        val cosZen = sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(ha)
        val zenith = Math.toDegrees(asin(cosZen.coerceIn(-1.0, 1.0)).let { PI / 2 - it })
        // altitude = 90 - zenith
        val altitude = 90.0 - zenith

        val azY = -sin(ha)
        val azX = tan(decl) * cos(latR) - sin(latR) * cos(ha)
        var az = Math.toDegrees(atan2(azY, azX))
        if (az < 0) az += 360.0

        return SolarPos(
            azimuthDeg = GeoMath.clampBearing(az),
            altitudeDeg = altitude,
            zenithDeg = zenith
        )
    }

    private fun julianDay(year: Int, month: Int, day: Int, hour: Double, tzOffsetH: Double): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val A = y / 100
        val B = 2 - A + A / 4
        val dayFrac = day + (hour - tzOffsetH) / 24.0
        return (365.25 * (y + 4716)).toInt() + (30.6001 * (m + 1)).toInt() + dayFrac + B - 1524.5
    }
}
