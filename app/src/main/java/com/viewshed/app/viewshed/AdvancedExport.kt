package com.viewshed.app.viewshed

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Binary Phase 7 exports that do not require a third-party GIS runtime. */
object AdvancedExport {
    fun toKmz(results: List<ViewshedResult>): ByteArray = zip(
        mapOf("doc.kml" to GeoExport.toKml(results).toByteArray(StandardCharsets.UTF_8)),
    )

    fun toAnalysisBundle(results: List<ViewshedResult>, multiObserver: Boolean): ByteArray = zip(
        mapOf(
            "viewshed.geojson" to GeoExport.toGeoJson(results, multiObserver).toByteArray(),
            "viewshed.kml" to GeoExport.toKml(results).toByteArray(),
            "viewshed.gpx" to GeoExport.toGpx(results).toByteArray(),
            "viewshed.csv" to GeoExport.toCsv(results).toByteArray(),
            "README.txt" to (
                "Viewshade field analysis bundle\n" +
                    "Coordinates: WGS84 longitude/latitude\n" +
                    "The polygons contain sampled visibility sectors, not legal survey boundaries.\n"
                ).toByteArray(),
        ),
    )

    /** ESRI Polygon SHP + SHX + DBF + WGS84 PRJ in a portable ZIP. */
    fun toShapefileZip(results: List<ViewshedResult>): ByteArray {
        val features = results.mapIndexedNotNull { index, result ->
            val rings = result.visibleSectors.map { close(it.boundary) }
                .ifEmpty { listOf(close(result.boundary)) }
                .filter { it.size >= 4 }
            rings.takeIf { it.isNotEmpty() }?.let { ShapeFeature(index + 1, result, it) }
        }
        val shape = writeShape(features)
        return zip(
            mapOf(
                "viewshed.shp" to shape.shp,
                "viewshed.shx" to shape.shx,
                "viewshed.dbf" to writeDbf(features),
                "viewshed.prj" to WGS84_PRJ.toByteArray(StandardCharsets.US_ASCII),
                "viewshed.cpg" to "UTF-8".toByteArray(StandardCharsets.US_ASCII),
            ),
        )
    }

    private fun writeShape(features: List<ShapeFeature>): ShapeFiles {
        val bounds = bounds(features.flatMap { it.rings }.flatten())
        val records = features.map { feature -> polygonRecord(feature.rings) }
        val shpBytes = 100 + records.sumOf { 8 + it.size }
        val shp = ByteBuffer.allocate(shpBytes).order(ByteOrder.BIG_ENDIAN)
        writeHeader(shp, shpBytes, bounds)
        var offsetWords = 50
        val indexEntries = ArrayList<Pair<Int, Int>>(records.size)
        records.forEachIndexed { index, record ->
            val contentWords = record.size / 2
            shp.order(ByteOrder.BIG_ENDIAN).putInt(index + 1).putInt(contentWords)
            shp.put(record)
            indexEntries += offsetWords to contentWords
            offsetWords += 4 + contentWords
        }

        val shxBytes = 100 + indexEntries.size * 8
        val shx = ByteBuffer.allocate(shxBytes).order(ByteOrder.BIG_ENDIAN)
        writeHeader(shx, shxBytes, bounds)
        indexEntries.forEach { (offset, length) -> shx.putInt(offset).putInt(length) }
        return ShapeFiles(shp.array(), shx.array())
    }

    private fun writeHeader(buffer: ByteBuffer, byteLength: Int, bounds: Bounds) {
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(9994)
        repeat(5) { buffer.putInt(0) }
        buffer.putInt(byteLength / 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(1000)
        buffer.putInt(5)
        buffer.putDouble(bounds.minX).putDouble(bounds.minY)
        buffer.putDouble(bounds.maxX).putDouble(bounds.maxY)
        repeat(4) { buffer.putDouble(0.0) }
    }

    private fun polygonRecord(rings: List<List<GeoPoint>>): ByteArray {
        val points = rings.flatten()
        val bounds = bounds(points)
        val size = 4 + 32 + 4 + 4 + rings.size * 4 + points.size * 16
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(5)
        buffer.putDouble(bounds.minX).putDouble(bounds.minY)
        buffer.putDouble(bounds.maxX).putDouble(bounds.maxY)
        buffer.putInt(rings.size).putInt(points.size)
        var offset = 0
        rings.forEach { ring ->
            buffer.putInt(offset)
            offset += ring.size
        }
        points.forEach { point -> buffer.putDouble(point.lon).putDouble(point.lat) }
        return buffer.array()
    }

    private fun writeDbf(features: List<ShapeFeature>): ByteArray {
        val fields = listOf(
            DbfField("ID", 'N', 6, 0),
            DbfField("AREA_KM2", 'N', 14, 5),
            DbfField("EYE_M", 'N', 10, 2),
            DbfField("MAX_KM", 'N', 10, 2),
        )
        val headerLength = 32 + fields.size * 32 + 1
        val recordLength = 1 + fields.sumOf { it.length }
        val buffer = ByteBuffer.allocate(headerLength + recordLength * features.size + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        buffer.put(0x03.toByte())
        buffer.put((now.get(Calendar.YEAR) - 1900).toByte())
        buffer.put((now.get(Calendar.MONTH) + 1).toByte())
        buffer.put(now.get(Calendar.DAY_OF_MONTH).toByte())
        buffer.putInt(features.size)
        buffer.putShort(headerLength.toShort())
        buffer.putShort(recordLength.toShort())
        repeat(20) { buffer.put(0.toByte()) }
        fields.forEach { field ->
            val name = field.name.toByteArray(StandardCharsets.US_ASCII).copyOf(11)
            buffer.put(name)
            buffer.put(field.type.code.toByte())
            buffer.putInt(0)
            buffer.put(field.length.toByte())
            buffer.put(field.decimals.toByte())
            repeat(14) { buffer.put(0.toByte()) }
        }
        buffer.put(0x0D.toByte())
        features.forEach { feature ->
            buffer.put(0x20.toByte())
            putDbfValue(buffer, feature.id.toString(), fields[0])
            putDbfValue(buffer, String.format(java.util.Locale.US, "%.5f", feature.result.stats.areaKm2), fields[1])
            putDbfValue(buffer, String.format(java.util.Locale.US, "%.2f", feature.result.params.eyeHeightM), fields[2])
            putDbfValue(buffer, String.format(java.util.Locale.US, "%.2f", feature.result.stats.maxRangeKm), fields[3])
        }
        buffer.put(0x1A.toByte())
        return buffer.array()
    }

    private fun putDbfValue(buffer: ByteBuffer, value: String, field: DbfField) {
        val text = value.take(field.length).padStart(field.length, ' ')
        buffer.put(text.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun close(points: List<GeoPoint>): List<GeoPoint> = when {
        points.isEmpty() || points.first() == points.last() -> points
        else -> points + points.first()
    }

    private fun bounds(points: List<GeoPoint>): Bounds = if (points.isEmpty()) {
        Bounds(0.0, 0.0, 0.0, 0.0)
    } else {
        Bounds(points.minOf { it.lon }, points.minOf { it.lat }, points.maxOf { it.lon }, points.maxOf { it.lat })
    }

    private data class ShapeFeature(val id: Int, val result: ViewshedResult, val rings: List<List<GeoPoint>>)
    private data class ShapeFiles(val shp: ByteArray, val shx: ByteArray)
    private data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)
    private data class DbfField(val name: String, val type: Char, val length: Int, val decimals: Int)

    private const val WGS84_PRJ =
        "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]"
}
