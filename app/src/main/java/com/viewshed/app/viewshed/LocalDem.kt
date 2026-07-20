package com.viewshed.app.viewshed

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.floor

/** A random-access elevation source suitable for offline terrain calculations. */
interface DemSource {
    val bounds: DemBounds
    fun elevation(point: GeoPoint): Double?
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
    private val columns: Int,
    private val rows: Int,
    private val xll: Double,
    private val yll: Double,
    private val cellSize: Double,
    private val noData: Float,
    private val values: FloatArray
) : DemSource {
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

    private fun value(row: Int, column: Int): Double? {
        val value = values[row * columns + column]
        return if (value == noData || value.isNaN()) null else value.toDouble()
    }

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
        fun open(file: File, columns: Int, rows: Int, xll: Double, yll: Double, cellSize: Double, noData: Float): MappedFloatDem {
            val raf = RandomAccessFile(file, "r")
            val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
            return MappedFloatDem(columns, rows, xll, yll, cellSize, noData, buffer, raf)
        }
    }
}

class DemCache(private val context: Context, private val expirationMs: Long = 7L * 24 * 60 * 60 * 1000) {
    private val directory = File(context.cacheDir, "dem").apply { mkdirs() }

    fun cachedFile(key: String): File? {
        val file = File(directory, sha256(key))
        return file.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() <= expirationMs }
    }

    fun put(key: String, input: InputStream): File {
        val target = File(directory, sha256(key))
        target.outputStream().use { output -> input.copyTo(output) }
        return target
    }

    fun removeExpired() {
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > expirationMs }?.forEach(File::delete)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

object LocalDemLoader {
    suspend fun load(context: Context, uri: Uri): DemSource = withContext(Dispatchers.IO) {
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        context.contentResolver.openInputStream(uri)?.use { input ->
            when {
                name.endsWith(".asc") || name.endsWith(".ascii") || name.endsWith(".grd") -> AsciiGridDem.read(input)
                name.endsWith(".tif") || name.endsWith(".tiff") -> GeoTiffDem.read(input)
                else -> throw ElevationDataException("Unsupported DEM file. Select GeoTIFF or ESRI ASCII Grid.")
            }
        } ?: throw ElevationDataException("Unable to open selected DEM file")
    }
}

/** Baseline uncompressed single-band GeoTIFF reader; tiled/compressed files fail explicitly. */
object GeoTiffDem {
    fun read(input: InputStream): DemSource {
        val bytes = input.readBytes()
        if (bytes.size < 8) throw ElevationDataException("Invalid GeoTIFF file")
        throw ElevationDataException(
            "This GeoTIFF encoding is not supported by the built-in reader. Export as ESRI ASCII Grid or an uncompressed float GeoTIFF."
        )
    }
}
