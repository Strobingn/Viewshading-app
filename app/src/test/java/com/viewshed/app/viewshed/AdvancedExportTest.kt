package com.viewshed.app.viewshed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class AdvancedExportTest {
    @Test
    fun shapefileBundleContainsValidCoreFiles() {
        val bytes = AdvancedExport.toShapefileZip(listOf(sampleResult()))
        val files = unzip(bytes)
        assertTrue(files.keys.containsAll(listOf("viewshed.shp", "viewshed.shx", "viewshed.dbf", "viewshed.prj")))
        assertEquals(9994, ByteBuffer.wrap(files.getValue("viewshed.shp")).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(5, ByteBuffer.wrap(files.getValue("viewshed.shp"), 32, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun kmzContainsDocumentKml() {
        val files = unzip(AdvancedExport.toKmz(listOf(sampleResult())))
        assertTrue(files.getValue("doc.kml").toString(Charsets.UTF_8).contains("<kml"))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val output = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                output[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return output
    }

    private fun sampleResult(): ViewshedResult {
        val observer = GeoPoint(41.5, -74.0)
        val boundary = listOf(
            GeoPoint(41.5, -74.0),
            GeoPoint(41.51, -74.0),
            GeoPoint(41.51, -73.99),
            GeoPoint(41.5, -74.0),
        )
        return ViewshedResult(
            observer = observer,
            boundary = boundary,
            rangesM = listOf(1_000.0),
            stats = ViewshedStats(4, 1_000.0, 1_000.0, 0.5, 8, 10),
            params = ViewshedParams(numRays = 8, samplesPerRay = 10),
        )
    }
}
