package com.viewshed.app.viewshed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldToolsTest {

    @Test
    fun sampleGrid_covers_center_and_stays_in_radius() {
        val center = GeoPoint(41.5, -74.0)
        val pts = OfflineMapCache.sampleGrid(center, radiusKm = 1.0, steps = 12)
        assertTrue(pts.size > 20)
        assertTrue(pts.any { GeoMath.distanceM(it, center) < 1.0 })
        assertTrue(pts.all { GeoMath.distanceM(it, center) <= 1000.0 * 1.05 })
    }

    @Test
    fun measure_distance_known_baseline() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoMath.destination(a, 90.0, 1000.0)
        val d = MeasurementTool.calculateDistanceM(a, b)
        assertEquals(1000.0, d, 5.0)
        assertTrue(MeasurementTool.formatDistance(d).contains("km") || MeasurementTool.formatDistance(d).contains("m"))
    }

    @Test
    fun pack_summary_empty() {
        assertEquals("No offline packs", OfflineMapCache.packSummary(emptyList()))
    }

    @Test
    fun measure_area_triangle_positive() {
        val ring = listOf(
            GeoPoint(41.50, -74.01),
            GeoPoint(41.51, -74.01),
            GeoPoint(41.505, -74.00)
        )
        assertTrue(MeasurementTool.calculateAreaM2(ring) > 0.0)
    }
}
