package com.viewshed.app.viewshed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewshedEngineTest {

    @Test
    fun destination_north_moves_latitude_up() {
        val start = GeoPoint(41.5, -74.0)
        val dest = GeoMath.destination(start, 0.0, 1000.0)
        assertTrue(dest.lat > start.lat)
        assertEquals(start.lon, dest.lon, 0.01)
    }

    @Test
    fun flat_terrain_sees_full_range() {
        val observer = GeoPoint(41.5, -74.0)
        val params = ViewshedParams(
            eyeHeightM = 2.0,
            targetHeightM = 0.0,
            maxDistKm = 1.0,
            numRays = 36,
            samplesPerRay = 20,
            useCurvature = false,
            parallelRays = false
        )
        val flat = 100.0
        val points = ViewshedEngine.samplePoints(observer, params)
        val elev = points.associate { it.key() to flat }
        val result = ViewshedEngine.compute(observer, params, elev)
        assertTrue(result.stats.maxRangeM > 900.0)
        assertTrue(result.stats.areaKm2 > 0.0)
    }

    @Test
    fun ridge_blocks_beyond() {
        val observer = GeoPoint(0.0, 0.0)
        val params = ViewshedParams(
            eyeHeightM = 1.0,
            targetHeightM = 0.0,
            maxDistKm = 1.0,
            numRays = 4,
            samplesPerRay = 20,
            useCurvature = false,
            parallelRays = false
        )
        fun elev(p: GeoPoint): Double {
            val d = GeoMath.distanceM(observer, p)
            return when {
                d in 200.0..350.0 -> 50.0
                else -> 0.0
            }
        }
        val points = ViewshedEngine.samplePoints(observer, params)
        val map = points.associate { it.key() to elev(it) }
        val result = ViewshedEngine.compute(observer, params, map)
        assertTrue(
            "Expected blockage well short of 1km, got ${result.stats.maxRangeM}",
            result.stats.maxRangeM < 600.0
        )
    }

    @Test
    fun parallel_matches_serial_on_flat() {
        val observer = GeoPoint(41.5, -74.0)
        val base = ViewshedParams(
            eyeHeightM = 2.0,
            maxDistKm = 0.5,
            numRays = 24,
            samplesPerRay = 15,
            useCurvature = false
        )
        val elev = ViewshedEngine.samplePoints(observer, base)
            .associate { it.key() to 50.0 }
        val serial = ViewshedEngine.compute(observer, base.copy(parallelRays = false), elev)
        val parallel = ViewshedEngine.compute(observer, base.copy(parallelRays = true), elev)
        assertEquals(serial.stats.maxRangeM, parallel.stats.maxRangeM, 1.0)
    }

    @Test
    fun quality_presets_apply_ray_counts() {
        val p = ViewshedParams().withQuality(SampleQuality.HIGH)
        assertEquals(SampleQuality.HIGH.rays, p.numRays)
        assertEquals(SampleQuality.HIGH.samples, p.samplesPerRay)
    }
}
