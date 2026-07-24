package com.viewshed.app.viewshed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class MappedFloatDemTest {
    @Test
    fun mappedAsciiRasterPreservesCellsAndBilinearLookup() {
        val asc = """
            ncols 2
            nrows 2
            xllcorner -74.0
            yllcorner 41.0
            cellsize 0.01
            NODATA_value -9999
            10 20
            30 40
        """.trimIndent()
        val source = AsciiGridDem.read(ByteArrayInputStream(asc.toByteArray()))
        val directory = Files.createTempDirectory("mapped-dem").toFile()
        val mapped = MappedFloatDem.create(directory.resolve("terrain.float"), source)
        try {
            assertEquals(10.0, mapped.elevationAt(0, 0)!!, 0.001)
            assertEquals(40.0, mapped.elevationAt(1, 1)!!, 0.001)
            assertNotNull(mapped.elevation(GeoPoint(41.01, -73.99)))
        } finally {
            mapped.close()
            directory.deleteRecursively()
        }
    }
}
