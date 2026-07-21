package com.viewshed.app.viewshed

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mil.nga.tiff.TiffReader
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A random-access elevation source suitable for offline terrain calculations. */
interface DemSource {
    val bounds: DemBounds
    fun elevation(point: GeoPoint): Double?
}

/** A DEM whose native cells can also be rendered without first materializing map samples. */
interface RasterDemSource : DemSource {
    val columns: Int
    val rows: Int
    fun elevationAt(row: Int, column: Int): Double?
    fun pointAt(row: Int, column: Int): GeoPoint
}

data class DemBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    fun contains(point: GeoPoint): Boolean =
        point.lat in south..north && point.lon in west..east
}

/** ESRI ASCII Grid reader with bilinear interpolation. */
class AsciiGridDem private constructor(
    override val columns: Int,
    override val rows: Int,
    private val xll: Double,
    private val yll: Double,
    private val cellSize: Double,
    private val noData: Float,
    private val values: FloatArray
) : RasterDemSource {
    override val bounds = DemBounds(
        south = yll,
        west = xll,
        north = yll + rows * cellSize,
        east = xll + columns * cellSize
    )

    override fun elevation(point: GeoPoint): Double? {
        if (!bounds.contains(point)) return null
        val x = (point.lon - xll) / cellSize
        val yFromBottom = (point.lat - yll) / cellSize
        val row = rows - 1.0 - yFromBottom
        val x0 = floor(x).toInt().coerceIn(0, columns - 1)
        val y0 = floor(row).toInt().coerceIn(0, rows - 1)
        val x1 = (x0 + 1).coerceAtMost(columns - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val tx = (x - x0).coerceIn(0.0, 1.0)
        val ty = (row - y0).coerceIn(0.0, 1.0)
        val q00 = value(y0, x0) ?: return null
        val q10 = value(y0, x1) ?: return null
        val q01 = value(y1, x0) ?: return null
        val q11 = value(y1, x1) ?: return null
        val top = q00 * (1.0 - tx) + q10 * tx
        val bottom = q01 * (1.0 - tx) + q11 * tx
        return top * (1.0 - ty) + bottom * ty
    }

    override fun elevationAt(row: Int, column: Int): Double? {
        if (row !in 0 until rows || column !in 0 until columns) return null
        val value = values[row * columns + column]
        return if (value == noData || value.isNaN()) null else value.toDouble()
    }

    override fun pointAt(row: Int, column: Int): GeoPoint = GeoPoint(
        lat = yll + (rows - row - 0.5) * cellSize,
        lon = xll + (column + 0.5) * cellSize,
    )

    private fun value(row: Int, column: Int): Double? = elevationAt(row, column)

    companion object {
        fun read(input: InputStream): AsciiGridDem {
            val reader = BufferedReader(InputStreamReader(input))
            val header = linkedMapOf<String, String>()
            repeat(6) {
                val parts = reader.readLine()?.trim()?.split(Regex("\\s+"))
                    ?: throw ElevationDataException("Invalid ASCII Grid header")
                require(parts.size >= 2) { "Invalid ASCII Grid header line" }
                header[parts[0].lowercase()] = parts[1]
            }
            val columns = header.getValue("ncols").toInt()
            val rows = header.getValue("nrows").toInt()
            val cellSize = header.getValue("cellsize").toDouble()
            val xll = (header["xllcorner"] ?: header["xllcenter"]
                ?: error("Missing x origin")).toDouble()
            val yll = (header["yllcorner"] ?: header["yllcenter"]
                ?: error("Missing y origin")).toDouble()
            val noData = (header["nodata_value"] ?: "-9999").toFloat()
            val values = FloatArray(columns * rows)
            var index = 0
            reader.forEachLine { line ->
                line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
                    if (index < values.size) values[index++] = token.toFloat()
                }
            }
            if (index != values.size) {
                throw ElevationDataException("ASCII Grid contained $index values; expected ${values.size}")
            }
            return AsciiGridDem(columns, rows, xll, yll, cellSize, noData, values)
        }
    }
}

/** Compact memory-mapped cache used after parsing a local DEM. */
class MappedFloatDem private constructor(
    private val columns: Int,
    private val rows: Int,
    private val xll: Double,
    private val yll: Double,
    private val cellSize: Double,
    private val noData: Float,
    private val buffer: ByteBuffer,
    private val file: RandomAccessFile
) : DemSource, AutoCloseable {
    override val bounds = DemBounds(yll, xll, yll + rows * cellSize, xll + columns * cellSize)

    override fun elevation(point: GeoPoint): Double? {
        if (!bounds.contains(point)) return null
        val column = ((point.lon - xll) / cellSize).toInt().coerceIn(0, columns - 1)
        val row = (rows - 1 - ((point.lat - yll) / cellSize).toInt()).coerceIn(0, rows - 1)
        val value = buffer.getFloat((row * columns + column) * Float.SIZE_BYTES)
        return if (value == noData || value.isNaN()) null else value.toDouble()
    }

    override fun close() = file.close()

    companion object {
        fun open(
            file: File,
            columns: Int,
            rows: Int,
            xll: Double,
            yll: Double,
            cellSize: Double,
            noData: Float
        ): MappedFloatDem {
            val raf = RandomAccessFile(file, "r")
            val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(ByteOrder.LITTLE_ENDIAN)
            return MappedFloatDem(columns, rows, xll, yll, cellSize, noData, buffer, raf)
        }
    }
}

class DemCache(
    context: Context,
    private val expirationMs: Long = 7L * 24 * 60 * 60 * 1000
) {
    private val directory = File(context.cacheDir, "dem").apply { mkdirs() }

    fun cachedFile(key: String): File? {
        val file = File(directory, sha256(key))
        return file.takeIf {
            it.exists() && System.currentTimeMillis() - it.lastModified() <= expirationMs
        }
    }

    fun put(key: String, input: InputStream): File {
        val target = File(directory, sha256(key))
        target.outputStream().use { output -> input.copyTo(output) }
        return target
    }

    fun removeExpired() {
        directory.listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > expirationMs }
            ?.forEach(File::delete)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

object LocalDemLoader {
    suspend fun load(context: Context, uri: Uri): DemSource = withContext(Dispatchers.IO) {
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        context.contentResolver.openInputStream(uri)?.use { input ->
            when {
                name.endsWith(".asc") || name.endsWith(".ascii") || name.endsWith(".grd") ->
                    AsciiGridDem.read(input)
                name.endsWith(".tif") || name.endsWith(".tiff") -> GeoTiffDem.read(input)
                else -> throw ElevationDataException(
                    "Unsupported DEM file. Select GeoTIFF or ESRI ASCII Grid."
                )
            }
        } ?: throw ElevationDataException("Unable to open selected DEM file")
    }

    suspend fun load(file: File): DemSource = withContext(Dispatchers.IO) {
        file.inputStream().buffered().use { input ->
            when (file.extension.lowercase()) {
                "asc", "ascii", "grd" -> AsciiGridDem.read(input)
                "tif", "tiff" -> GeoTiffDem.read(input)
                else -> throw ElevationDataException(
                    "Unsupported DEM file. Select GeoTIFF or ESRI ASCII Grid.",
                )
            }
        }
    }
}

/**
 * GeoTIFF DEM backed by NGA's Android-compatible TIFF decoder.
 *
 * Supported raster layouts include strips and tiles with raw, LZW, Deflate, and PackBits
 * compression. GeoTIFF ModelPixelScale/ModelTiepoint or ModelTransformation tags are used
 * for georeferencing. Geographic WGS84 and WGS84 UTM EPSG 326xx/327xx rasters are accepted.
 */
class GeoTiffDem private constructor(
    override val columns: Int,
    override val rows: Int,
    private val values: FloatArray,
    private val noData: Double?,
    private val transform: RasterTransform,
    private val projection: DemProjection
) : RasterDemSource {

    override val bounds: DemBounds = calculateBounds()

    override fun elevation(point: GeoPoint): Double? {
        if (!bounds.contains(point)) return null
        val model = projection.fromWgs84(point)
        val pixel = transform.modelToPixel(model.first, model.second) ?: return null
        val x = pixel.first - transform.pixelCenterOffset
        val y = pixel.second - transform.pixelCenterOffset
        if (x < 0.0 || y < 0.0 || x > columns - 1.0 || y > rows - 1.0) return null

        val x0 = floor(x).toInt().coerceIn(0, columns - 1)
        val y0 = floor(y).toInt().coerceIn(0, rows - 1)
        val x1 = (x0 + 1).coerceAtMost(columns - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val tx = (x - x0).coerceIn(0.0, 1.0)
        val ty = (y - y0).coerceIn(0.0, 1.0)

        val q00 = value(y0, x0) ?: return nearestValid(x, y)
        val q10 = value(y0, x1) ?: return nearestValid(x, y)
        val q01 = value(y1, x0) ?: return nearestValid(x, y)
        val q11 = value(y1, x1) ?: return nearestValid(x, y)
        val top = q00 * (1.0 - tx) + q10 * tx
        val bottom = q01 * (1.0 - tx) + q11 * tx
        return top * (1.0 - ty) + bottom * ty
    }

    override fun elevationAt(row: Int, column: Int): Double? {
        if (row !in 0 until rows || column !in 0 until columns) return null
        val value = values[row * columns + column].toDouble()
        if (!value.isFinite()) return null
        if (noData != null && kotlin.math.abs(value - noData) <= NODATA_EPSILON) return null
        return value
    }

    override fun pointAt(row: Int, column: Int): GeoPoint {
        val model = transform.pixelToModel(
            column + transform.pixelCenterOffset,
            row + transform.pixelCenterOffset,
        )
        return projection.toWgs84(model.first, model.second)
    }

    private fun value(row: Int, column: Int): Double? = elevationAt(row, column)

    private fun nearestValid(x: Double, y: Double): Double? {
        val centerX = x.toInt().coerceIn(0, columns - 1)
        val centerY = y.toInt().coerceIn(0, rows - 1)
        for (radius in 0..2) {
            for (row in (centerY - radius).coerceAtLeast(0)..(centerY + radius).coerceAtMost(rows - 1)) {
                for (column in (centerX - radius).coerceAtLeast(0)..(centerX + radius).coerceAtMost(columns - 1)) {
                    value(row, column)?.let { return it }
                }
            }
        }
        return null
    }

    private fun calculateBounds(): DemBounds {
        val corners = listOf(
            0.0 to 0.0,
            columns.toDouble() to 0.0,
            0.0 to rows.toDouble(),
            columns.toDouble() to rows.toDouble()
        ).map { (x, y) ->
            val model = transform.pixelToModel(x, y)
            projection.toWgs84(model.first, model.second)
        }
        return DemBounds(
            south = corners.minOf { it.lat },
            west = corners.minOf { it.lon },
            north = corners.maxOf { it.lat },
            east = corners.maxOf { it.lon }
        )
    }

    companion object {
        private const val NODATA_EPSILON = 1e-6

        fun read(input: InputStream): GeoTiffDem {
            val bytes = input.readBytes()
            if (bytes.size < 8) throw ElevationDataException("Invalid GeoTIFF file")

            try {
                val metadata = GeoTiffMetadata.parse(bytes)
                val image = TiffReader.readTiff(bytes)
                val directory = image.fileDirectories.firstOrNull()
                    ?: throw ElevationDataException("GeoTIFF contains no image directory")
                val columns = directory.imageWidth.toInt()
                val rows = directory.imageHeight.toInt()
                if (columns <= 0 || rows <= 0) {
                    throw ElevationDataException("GeoTIFF has invalid dimensions")
                }
                if (directory.samplesPerPixel < 1) {
                    throw ElevationDataException("GeoTIFF has no raster sample band")
                }

                val rasters = directory.readRasters()
                val values = FloatArray(columns * rows)
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        values[row * columns + column] =
                            rasters.getPixel(column, row)[0].toFloat()
                    }
                }

                return GeoTiffDem(
                    columns = columns,
                    rows = rows,
                    values = values,
                    noData = metadata.noData,
                    transform = metadata.transform,
                    projection = metadata.projection
                )
            } catch (error: ElevationDataException) {
                throw error
            } catch (error: Exception) {
                throw ElevationDataException(
                    "Unable to decode GeoTIFF: ${error.message ?: error.javaClass.simpleName}",
                    error
                )
            }
        }
    }
}

private data class GeoTiffMetadata(
    val transform: RasterTransform,
    val projection: DemProjection,
    val noData: Double?
) {
    companion object {
        private const val TAG_MODEL_PIXEL_SCALE = 33550
        private const val TAG_MODEL_TIEPOINT = 33922
        private const val TAG_MODEL_TRANSFORMATION = 34264
        private const val TAG_GEO_KEY_DIRECTORY = 34735
        private const val TAG_GDAL_NODATA = 42113

        private const val KEY_GT_RASTER_TYPE = 1025
        private const val KEY_GEOGRAPHIC_TYPE = 2048
        private const val KEY_PROJECTED_CS_TYPE = 3072
        private const val RASTER_PIXEL_IS_POINT = 2

        fun parse(bytes: ByteArray): GeoTiffMetadata {
            val tags = ClassicTiffTags(bytes)
            val keys = parseGeoKeys(tags.shorts(TAG_GEO_KEY_DIRECTORY))
            val pixelCenterOffset = if (keys[KEY_GT_RASTER_TYPE] == RASTER_PIXEL_IS_POINT) 0.0 else 0.5

            val transformValues = tags.doubles(TAG_MODEL_TRANSFORMATION)
            val transform = if (transformValues.size >= 16) {
                RasterTransform.fromMatrix(transformValues, pixelCenterOffset)
            } else {
                val scale = tags.doubles(TAG_MODEL_PIXEL_SCALE)
                val tie = tags.doubles(TAG_MODEL_TIEPOINT)
                if (scale.size < 2 || tie.size < 6) {
                    throw ElevationDataException(
                        "GeoTIFF is missing ModelPixelScale/ModelTiepoint or ModelTransformation tags"
                    )
                }
                RasterTransform.fromScaleAndTiepoint(scale, tie, pixelCenterOffset)
            }

            val projectedEpsg = keys[KEY_PROJECTED_CS_TYPE]
            val geographicEpsg = keys[KEY_GEOGRAPHIC_TYPE]
            val projection = when {
                projectedEpsg != null -> DemProjection.fromEpsg(projectedEpsg)
                geographicEpsg == null || geographicEpsg == 4326 -> GeographicProjection
                else -> throw ElevationDataException(
                    "Unsupported geographic CRS EPSG:$geographicEpsg. Reproject the DEM to EPSG:4326."
                )
            }
            return GeoTiffMetadata(
                transform = transform,
                projection = projection,
                noData = tags.ascii(TAG_GDAL_NODATA)?.trim()?.trimEnd('\u0000')?.toDoubleOrNull()
            )
        }

        private fun parseGeoKeys(values: IntArray): Map<Int, Int> {
            if (values.size < 4) return emptyMap()
            val count = values[3]
            val output = mutableMapOf<Int, Int>()
            for (index in 0 until count) {
                val offset = 4 + index * 4
                if (offset + 3 >= values.size) break
                val keyId = values[offset]
                val tagLocation = values[offset + 1]
                val valueCount = values[offset + 2]
                val valueOffset = values[offset + 3]
                if (tagLocation == 0 && valueCount == 1) output[keyId] = valueOffset
            }
            return output
        }
    }
}

private class RasterTransform private constructor(
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val d: Double,
    private val e: Double,
    private val f: Double,
    val pixelCenterOffset: Double
) {
    fun pixelToModel(pixelX: Double, pixelY: Double): Pair<Double, Double> =
        (a * pixelX + b * pixelY + c) to (d * pixelX + e * pixelY + f)

    fun modelToPixel(modelX: Double, modelY: Double): Pair<Double, Double>? {
        val determinant = a * e - b * d
        if (kotlin.math.abs(determinant) < 1e-15) return null
        val dx = modelX - c
        val dy = modelY - f
        return ((e * dx - b * dy) / determinant) to ((-d * dx + a * dy) / determinant)
    }

    companion object {
        fun fromScaleAndTiepoint(
            scale: DoubleArray,
            tie: DoubleArray,
            pixelCenterOffset: Double
        ): RasterTransform {
            val rasterX = tie[0]
            val rasterY = tie[1]
            val modelX = tie[3]
            val modelY = tie[4]
            val scaleX = scale[0]
            val scaleY = scale[1]
            return RasterTransform(
                a = scaleX,
                b = 0.0,
                c = modelX - rasterX * scaleX,
                d = 0.0,
                e = -scaleY,
                f = modelY + rasterY * scaleY,
                pixelCenterOffset = pixelCenterOffset
            )
        }

        fun fromMatrix(matrix: DoubleArray, pixelCenterOffset: Double): RasterTransform =
            RasterTransform(
                a = matrix[0],
                b = matrix[1],
                c = matrix[3],
                d = matrix[4],
                e = matrix[5],
                f = matrix[7],
                pixelCenterOffset = pixelCenterOffset
            )
    }
}

private sealed interface DemProjection {
    fun fromWgs84(point: GeoPoint): Pair<Double, Double>
    fun toWgs84(x: Double, y: Double): GeoPoint

    companion object {
        fun fromEpsg(epsg: Int): DemProjection = when (epsg) {
            4326 -> GeographicProjection
            in 32601..32660 -> UtmProjection(epsg - 32600, northern = true)
            in 32701..32760 -> UtmProjection(epsg - 32700, northern = false)
            else -> throw ElevationDataException(
                "Unsupported projected CRS EPSG:$epsg. Reproject the DEM to EPSG:4326 or WGS84 UTM."
            )
        }
    }
}

private object GeographicProjection : DemProjection {
    override fun fromWgs84(point: GeoPoint): Pair<Double, Double> = point.lon to point.lat
    override fun toWgs84(x: Double, y: Double): GeoPoint = GeoPoint(y, x)
}

/** WGS84 UTM conversion for EPSG 326xx and 327xx GeoTIFF DEMs. */
private class UtmProjection(
    private val zone: Int,
    private val northern: Boolean
) : DemProjection {
    override fun fromWgs84(point: GeoPoint): Pair<Double, Double> {
        val lat = Math.toRadians(point.lat)
        val lon = Math.toRadians(point.lon)
        val lonOrigin = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val n = A / sqrt(1.0 - E2 * sin(lat).pow(2))
        val t = tan(lat).pow(2)
        val c = EP2 * cos(lat).pow(2)
        val a = cos(lat) * (lon - lonOrigin)
        val m = A * (
            (1 - E2 / 4 - 3 * E2.pow(2) / 64 - 5 * E2.pow(3) / 256) * lat -
                (3 * E2 / 8 + 3 * E2.pow(2) / 32 + 45 * E2.pow(3) / 1024) * sin(2 * lat) +
                (15 * E2.pow(2) / 256 + 45 * E2.pow(3) / 1024) * sin(4 * lat) -
                (35 * E2.pow(3) / 3072) * sin(6 * lat)
            )
        val easting = K0 * n * (
            a + (1 - t + c) * a.pow(3) / 6 +
                (5 - 18 * t + t.pow(2) + 72 * c - 58 * EP2) * a.pow(5) / 120
            ) + 500000.0
        var northing = K0 * (
            m + n * tan(lat) * (
                a.pow(2) / 2 + (5 - t + 9 * c + 4 * c.pow(2)) * a.pow(4) / 24 +
                    (61 - 58 * t + t.pow(2) + 600 * c - 330 * EP2) * a.pow(6) / 720
                )
            )
        if (!northern) northing += 10_000_000.0
        return easting to northing
    }

    override fun toWgs84(x: Double, y: Double): GeoPoint {
        val adjustedX = x - 500000.0
        val adjustedY = if (northern) y else y - 10_000_000.0
        val m = adjustedY / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * E2.pow(2) / 64 - 5 * E2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val n1 = A / sqrt(1 - E2 * sin(phi1).pow(2))
        val t1 = tan(phi1).pow(2)
        val c1 = EP2 * cos(phi1).pow(2)
        val r1 = A * (1 - E2) / (1 - E2 * sin(phi1).pow(2)).pow(1.5)
        val d = adjustedX / (n1 * K0)
        val lat = phi1 - (n1 * tan(phi1) / r1) * (
            d.pow(2) / 2 -
                (5 + 3 * t1 + 10 * c1 - 4 * c1.pow(2) - 9 * EP2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1.pow(2) - 252 * EP2 - 3 * c1.pow(2)) * d.pow(6) / 720
            )
        val lonOrigin = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val lon = lonOrigin + (
            d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1.pow(2) + 8 * EP2 + 24 * t1.pow(2)) * d.pow(5) / 120
            ) / cos(phi1)
        return GeoPoint(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    companion object {
        private const val A = 6378137.0
        private const val E2 = 0.00669437999014
        private const val K0 = 0.9996
        private const val EP2 = E2 / (1.0 - E2)
    }
}

/** Minimal classic-TIFF tag reader used only for GeoTIFF metadata tags. */
private class ClassicTiffTags(bytes: ByteArray) {
    private val data = ByteBuffer.wrap(bytes)
    private val entries = mutableMapOf<Int, Entry>()

    init {
        val order = when {
            bytes[0].toInt() == 0x49 && bytes[1].toInt() == 0x49 -> ByteOrder.LITTLE_ENDIAN
            bytes[0].toInt() == 0x4D && bytes[1].toInt() == 0x4D -> ByteOrder.BIG_ENDIAN
            else -> throw ElevationDataException("Invalid TIFF byte order marker")
        }
        data.order(order)
        if (ushort(2) != 42) {
            throw ElevationDataException("BigTIFF is not supported; use a classic GeoTIFF export")
        }
        val ifdOffset = uint(4).toInt()
        requireRange(ifdOffset, 2)
        val count = ushort(ifdOffset)
        for (index in 0 until count) {
            val offset = ifdOffset + 2 + index * 12
            requireRange(offset, 12)
            val tag = ushort(offset)
            val type = ushort(offset + 2)
            val valueCount = uint(offset + 4).toInt()
            val typeSize = TYPE_SIZES[type] ?: continue
            val byteCount = valueCount.toLong() * typeSize
            if (byteCount > Int.MAX_VALUE) continue
            val valueOffset = if (byteCount <= 4) offset + 8 else uint(offset + 8).toInt()
            if (valueOffset >= 0 && valueOffset.toLong() + byteCount <= bytes.size) {
                entries[tag] = Entry(type, valueCount, valueOffset)
            }
        }
    }

    fun doubles(tag: Int): DoubleArray {
        val entry = entries[tag] ?: return doubleArrayOf()
        return DoubleArray(entry.count) { index -> number(entry, index) }
    }

    fun shorts(tag: Int): IntArray {
        val entry = entries[tag] ?: return intArrayOf()
        return IntArray(entry.count) { index -> number(entry, index).toInt() }
    }

    fun ascii(tag: Int): String? {
        val entry = entries[tag] ?: return null
        if (entry.type != 2) return null
        val bytes = ByteArray(entry.count)
        val duplicate = data.duplicate()
        duplicate.position(entry.offset)
        duplicate.get(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun number(entry: Entry, index: Int): Double {
        val offset = entry.offset + index * (TYPE_SIZES[entry.type] ?: 1)
        return when (entry.type) {
            1 -> data.get(offset).toInt().and(0xFF).toDouble()
            3 -> ushort(offset).toDouble()
            4 -> uint(offset).toDouble()
            5 -> uint(offset).toDouble() / uint(offset + 4).toDouble()
            6 -> data.get(offset).toDouble()
            8 -> data.getShort(offset).toDouble()
            9 -> data.getInt(offset).toDouble()
            10 -> data.getInt(offset).toDouble() / data.getInt(offset + 4).toDouble()
            11 -> data.getFloat(offset).toDouble()
            12 -> data.getDouble(offset)
            else -> Double.NaN
        }
    }

    private fun ushort(offset: Int): Int = data.getShort(offset).toInt().and(0xFFFF)
    private fun uint(offset: Int): Long = data.getInt(offset).toLong().and(0xFFFF_FFFFL)

    private fun requireRange(offset: Int, length: Int) {
        if (offset < 0 || offset + length > data.capacity()) {
            throw ElevationDataException("Corrupt TIFF directory offset")
        }
    }

    private data class Entry(val type: Int, val count: Int, val offset: Int)

    companion object {
        private val TYPE_SIZES = mapOf(
            1 to 1,
            2 to 1,
            3 to 2,
            4 to 4,
            5 to 8,
            6 to 1,
            7 to 1,
            8 to 2,
            9 to 4,
            10 to 8,
            11 to 4,
            12 to 8
        )
    }
}
