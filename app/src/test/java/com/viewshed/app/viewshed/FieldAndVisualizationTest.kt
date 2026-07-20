package com.viewshed.app.viewshed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldAndVisualizationTest {
    @Test
    fun gpsQualityRejectsStaleOrInaccurateFixes() {
        assertTrue(GpsQualityGate.assess(4f, 1_000).accepted)
        assertFalse(GpsQualityGate.assess(40f, 1_000).accepted)
        assertFalse(GpsQualityGate.assess(4f, 180_000).accepted)
    }

    @Test
    fun gpxContainsWaypointsAndTracks() {
        val project = FieldProject(
            name = "Test",
            waypoints = listOf(FieldWaypoint(name = "Observer", point = GeoPoint(41.5, -74.0))),
            tracks = listOf(FieldTrack(name = "Walk", points = listOf(
                FieldTrackPoint(GeoPoint(41.5, -74.0)),
                FieldTrackPoint(GeoPoint(41.5001, -74.0001))
            )))
        )
        val gpx = FieldGpxExport.export(project)
        assertTrue(gpx.contains("<wpt"))
        assertTrue(gpx.contains("<trk>"))
        assertTrue(project.tracks.first().distanceM > 0.0)
    }

    @Test
    fun heatmapCountsVisibilityAcrossObservers() {
        val sample = VisibilitySample(GeoPoint(41.5, -74.0), 100.0, 50.0, 0.0, 0.0, true)
        val params = ViewshedParams(numRays = 8, samplesPerRay = 10)
        val result = ViewshedResult(
            observer = GeoPoint(41.499, -74.0), boundary = emptyList(), rangesM = listOf(100.0),
            stats = ViewshedStats(0, 100.0, 100.0, 0.0, 8, 10), params = params,
            visibilityRays = listOf(VisibilityRay(0.0, listOf(sample), 100.0))
        )
        val visualization = TerrainVisualization.build(listOf(result, result))
        assertEquals(1, visualization.heatmap.size)
        assertEquals(1.0, visualization.heatmap.first().visibilityRatio, 0.0001)
        assertTrue(visualization.profiles.isNotEmpty())
    }
}
