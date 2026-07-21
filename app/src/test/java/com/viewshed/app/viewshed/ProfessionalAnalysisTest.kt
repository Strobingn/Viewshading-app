package com.viewshed.app.viewshed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfessionalAnalysisTest {

    private fun flatGrid(height: Double = 100.0): ElevationGrid {
        // Nearest-neighbor falls back to demo when empty & useDemo — use explicit map
        val pts = mutableMapOf<String, Double>()
        for (lat in -2..2) {
            for (lon in -2..2) {
                val p = GeoPoint(lat * 0.01, lon * 0.01)
                pts[p.key()] = height
            }
        }
        // denser around origin
        for (i in 0..50) {
            for (j in 0..50) {
                val p = GeoPoint(-0.02 + i * 0.0008, -0.02 + j * 0.0008)
                pts[p.key()] = height
            }
        }
        return ElevationGrid(pts, useDemo = false)
    }

    @Test
    fun bearing_east_is_about_90() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoMath.destination(a, 90.0, 1000.0)
        val brg = GeoMath.bearingDeg(a, b)
        assertEquals(90.0, brg, 2.0)
    }

    @Test
    fun intervisibility_flat_is_visible() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoMath.destination(a, 45.0, 500.0)
        val grid = flatGrid(50.0)
        val r = ProfessionalAnalysis.intervisibility(
            a, b, grid, eyeHeightM = 2.0, targetHeightM = 2.0,
            samples = 40, useCurvature = false
        )
        assertTrue(r.visible)
        assertTrue(r.distanceM > 400)
    }

    @Test
    fun intervisibility_ridge_blocks() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoMath.destination(a, 0.0, 1000.0)
        val map = mutableMapOf<String, Double>()
        // Build a ridge between a and b
        for (s in 0..100) {
            val d = s * 10.0
            val p = GeoMath.destination(a, 0.0, d)
            val h = when {
                d in 400.0..550.0 -> 80.0
                else -> 0.0
            }
            map[p.key()] = h
        }
        map[a.key()] = 0.0
        map[b.key()] = 0.0
        val grid = ElevationGrid(map, useDemo = false)
        val r = ProfessionalAnalysis.intervisibility(
            a, b, grid, eyeHeightM = 1.0, targetHeightM = 1.0,
            samples = 50, useCurvature = false
        )
        assertFalse("ridge should block LOS", r.visible)
    }

    @Test
    fun solar_position_returns_finite() {
        val s = SolarPosition.at(41.5, -74.0, System.currentTimeMillis())
        assertTrue(s.azimuthDeg in 0.0..360.0)
        assertTrue(s.altitudeDeg > -90 && s.altitudeDeg < 90)
    }

    @Test
    fun weighted_stats_positive_after_compute() = runBlocking {
        val observer = GeoPoint(0.0, 0.0)
        val params = ViewshedParams(
            eyeHeightM = 2.0,
            maxDistKm = 0.5,
            numRays = 36,
            samplesPerRay = 20,
            useCurvature = false,
            parallelRays = false
        )
        val elevMap = ViewshedEngine.samplePoints(observer, params)
            .associate { it.key() to 10.0 }
        val grid = ElevationGrid(elevMap, useDemo = false)
        val result = ViewshedEngine.compute(observer, params, grid)
        val w = ProfessionalAnalysis.weightedStats(result)
        assertTrue(w.rawAreaKm2 >= 0.0)
        assertTrue(w.weightedScore >= 0.0)
    }

    @Test
    fun cumulative_needs_results() {
        val empty = ProfessionalAnalysis.cumulativeViewshed(emptyList())
        assertEquals(0, empty.observerCount)
        assertTrue(empty.cells.isEmpty())
    }

    @Test
    fun intervisibility_uses_max_horizon_not_intermediate_below_horizon() {
        // Intermediate dip below a near ridge must not, by itself, decide visibility;
        // only whether the target elev-angle clears the max intermediate horizon.
        val a = GeoPoint(0.0, 0.0)
        val b = GeoMath.destination(a, 0.0, 1000.0)
        val map = mutableMapOf<String, Double>()
        for (s in 0..100) {
            val d = s * 10.0
            val p = GeoMath.destination(a, 0.0, d)
            val h = when {
                d in 200.0..300.0 -> 30.0 // near ridge
                d in 500.0..600.0 -> 5.0  // dip (below horizon of ridge)
                else -> 0.0
            }
            map[p.key()] = h
        }
        map[a.key()] = 0.0
        map[b.key()] = 0.0
        val grid = ElevationGrid(map, useDemo = false)
        val r = ProfessionalAnalysis.intervisibility(
            a, b, grid, eyeHeightM = 1.0, targetHeightM = 1.0,
            samples = 50, useCurvature = false,
        )
        assertFalse("near ridge should block target at ground", r.visible)
        assertTrue(r.firstBlockDistM != null && r.firstBlockDistM!! < 400.0)
    }

    @Test
    fun shadow_analysis_requires_sun_up_or_empty() {
        // Night / sun-down → empty boundary (use polar winter-ish timestamp is hard;
        // just ensure API is stable when solar altitude may be low).
        val observer = GeoPoint(41.5, -74.0)
        val params = ViewshedParams(
            eyeHeightM = 2.0,
            maxDistKm = 0.5,
            numRays = 24,
            samplesPerRay = 20,
            useCurvature = false,
            parallelRays = false,
        )
        val elevMap = ViewshedEngine.samplePoints(observer, params)
            .associate { it.key() to 20.0 }
        val grid = ElevationGrid(elevMap, useDemo = false)
        val shadow = ProfessionalAnalysis.shadowAnalysis(observer, grid, params)
        assertTrue(shadow.solar.azimuthDeg in 0.0..360.0)
        if (shadow.sunUp) {
            assertTrue(shadow.shadowBoundary.isNotEmpty())
            assertEquals(shadow.shadowRangesM.size + 1, shadow.shadowBoundary.size) // closed ring
        } else {
            assertTrue(shadow.shadowBoundary.isEmpty())
        }
    }
}
