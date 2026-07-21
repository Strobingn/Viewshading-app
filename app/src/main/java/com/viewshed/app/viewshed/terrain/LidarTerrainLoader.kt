package com.viewshed.app.viewshed.terrain

import android.content.Context
import android.net.Uri
import com.github.mreutegg.laszip4j.LASReader
import com.viewshed.app.viewshed.DemBounds
import com.viewshed.app.viewshed.ElevationDataException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

enum class GroundSurfaceMode(val label: String) {
    SOURCE_CLASSIFIED("LAS class 2 ground"),
    AUTO_LOWEST("Automatic bare earth"),
    SURFACE_MODEL("Surface / structures"),
}

data class LidarImportOptions(
    val groundMode: GroundSurfaceMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
    val rasterResolution: Int = 1_024,
    val smoothingRadius: Int = 1,
) {
    fun sanitized() = copy(
        rasterResolution = rasterResolution.coerceIn(256, 2_048),
        smoothingRadius = smoothingRadius.coerceIn(0, 4),
    )
}

/** Streaming LAS/LAZ decoder and memory-bounded ground/surface rasterizer. */
object LidarTerrainLoader {
    suspend fun load(
        context: Context,
        uri: Uri,
        options: LidarImportOptions,
    ): TerrainRaster = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw ElevationDataException("Unable to open LAS/LAZ file")
        stream.use { raw ->
            val input = BufferedInputStream(raw, 256 * 1024)
            val header = readHeader(input)
                ?: throw ElevationDataException("The selected file is not a valid LAS/LAZ point cloud")
            val rasterizer = LidarRasterizer(header, options.sanitized())
            try {
                for (point in LASReader.getPoints(input)) {
                    val keepReading = rasterizer.add(
                        x = point.getX() * header.scaleX + header.offsetX,
                        y = point.getY() * header.scaleY + header.offsetY,
                        z = (point.getZ() * header.scaleZ + header.offsetZ).toFloat(),
                        classification = point.getClassification().toInt(),
                    )
                    if (!keepReading) break
                }
            } catch (error: Exception) {
                throw ElevationDataException(
                    "Unable to decode LAS/LAZ points: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            }
            rasterizer.finish(uri.lastPathSegment?.substringAfterLast('/') ?: "LiDAR")
        }
    }

    private fun readHeader(input: BufferedInputStream): LasHeader? {
        input.mark(4_096)
        val bytes = ByteArray(375)
        var read = 0
        while (read < bytes.size) {
            val count = input.read(bytes, read, bytes.size - read)
            if (count < 0) break
            read += count
        }
        input.reset()
        if (read < 227 || !bytes.copyOfRange(0, 4).contentEquals("LASF".toByteArray())) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val major = bytes[24].toInt() and 0xff
        val minor = bytes[25].toInt() and 0xff
        val pointFormat = (bytes[104].toInt() and 0xff) and 0x3f
        var pointCount = buffer.getInt(107).toLong() and 0xffff_ffffL
        if (major == 1 && minor >= 4 && read >= 255) {
            buffer.getLong(247).takeIf { it > 0 }?.let { pointCount = it }
        }
        return LasHeader(
            version = "$major.$minor",
            pointFormat = pointFormat,
            pointCount = pointCount,
            scaleX = buffer.getDouble(131),
            scaleY = buffer.getDouble(139),
            scaleZ = buffer.getDouble(147),
            offsetX = buffer.getDouble(155),
            offsetY = buffer.getDouble(163),
            offsetZ = buffer.getDouble(171),
            maxX = buffer.getDouble(179),
            minX = buffer.getDouble(187),
            maxY = buffer.getDouble(195),
            minY = buffer.getDouble(203),
        ).takeIf { header ->
            header.maxX > header.minX && header.maxY > header.minY &&
                listOf(
                    header.scaleX, header.scaleY, header.scaleZ,
                    header.offsetX, header.offsetY, header.offsetZ,
                    header.maxX, header.minX, header.maxY, header.minY,
                ).all(Double::isFinite)
        }
    }
}

private data class LasHeader(
    val version: String,
    val pointFormat: Int,
    val pointCount: Long,
    val scaleX: Double,
    val scaleY: Double,
    val scaleZ: Double,
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
)

private class LidarRasterizer(
    private val header: LasHeader,
    private val options: LidarImportOptions,
) {
    private val rangeX = header.maxX - header.minX
    private val rangeY = header.maxY - header.minY
    private val width: Int
    private val height: Int
    private val allMin: FloatArray
    private val allMax: FloatArray
    private val allCount: IntArray
    private val groundMin: FloatArray
    private val groundCount: IntArray
    private val histogram = LongArray(256)
    private val sampleStride = ceil(header.pointCount.coerceAtLeast(1).toDouble() / MAX_BINNED_POINTS)
        .toInt().coerceAtLeast(1)
    private var decoded = 0L
    private var binned = 0L
    private var ground = 0L

    init {
        if (rangeX >= rangeY) {
            width = options.rasterResolution
            height = (options.rasterResolution * rangeY / rangeX).roundToInt()
                .coerceIn(MIN_SHORT_SIDE, options.rasterResolution)
        } else {
            height = options.rasterResolution
            width = (options.rasterResolution * rangeX / rangeY).roundToInt()
                .coerceIn(MIN_SHORT_SIDE, options.rasterResolution)
        }
        allMin = FloatArray(width * height) { Float.POSITIVE_INFINITY }
        allMax = FloatArray(width * height) { Float.NEGATIVE_INFINITY }
        allCount = IntArray(width * height)
        groundMin = FloatArray(width * height) { Float.POSITIVE_INFINITY }
        groundCount = IntArray(width * height)
    }

    fun add(x: Double, y: Double, z: Float, classification: Int): Boolean {
        if (decoded >= MAX_DECODED_POINTS) return false
        val indexInFile = decoded++
        if (indexInFile % sampleStride != 0L || !x.isFinite() || !y.isFinite() || !z.isFinite()) return true
        val column = (((x - header.minX) / rangeX) * (width - 1)).toInt().coerceIn(0, width - 1)
        val row = ((1.0 - (y - header.minY) / rangeY) * (height - 1)).toInt().coerceIn(0, height - 1)
        val index = row * width + column
        allMin[index] = minOf(allMin[index], z)
        allMax[index] = maxOf(allMax[index], z)
        allCount[index]++
        binned++
        val normalizedClass = classification.coerceIn(0, 255)
        histogram[normalizedClass]++
        if (normalizedClass == 2 || normalizedClass == 8) {
            groundMin[index] = minOf(groundMin[index], z)
            groundCount[index]++
            ground++
        }
        return true
    }

    fun finish(name: String): TerrainRaster {
        if (binned == 0L) throw ElevationDataException("The LAS/LAZ file contains no usable points")
        val populated = allCount.count { it > 0 }
        val classifiedCells = groundCount.count { it > 0 }
        val usableGroundClasses = ground >= 100 && classifiedCells >= max(12, (populated * 0.08).roundToInt())
        val appliedMode = when {
            options.groundMode == GroundSurfaceMode.SOURCE_CLASSIFIED && usableGroundClasses ->
                GroundSurfaceMode.SOURCE_CLASSIFIED
            options.groundMode == GroundSurfaceMode.SOURCE_CLASSIFIED -> GroundSurfaceMode.AUTO_LOWEST
            else -> options.groundMode
        }
        val source = when (appliedMode) {
            GroundSurfaceMode.SOURCE_CLASSIFIED -> groundMin
            GroundSurfaceMode.AUTO_LOWEST -> allMin
            GroundSurfaceMode.SURFACE_MODEL -> allMax
        }
        val counts = if (appliedMode == GroundSurfaceMode.SOURCE_CLASSIFIED) groundCount else allCount
        val terrain = FloatArray(width * height) { index ->
            source[index].takeIf { counts[index] > 0 && it.isFinite() } ?: Float.NaN
        }
        fillMissingTerrain(terrain, width, height)
        val filtered = suppressLowOutliers(terrain, width, height)
        val smoothed = if (options.smoothingRadius > 0 && appliedMode != GroundSurfaceMode.SURFACE_MODEL) {
            boxSmooth(filtered, width, height, options.smoothingRadius)
        } else filtered
        val canopy = FloatArray(terrain.size)
        if (appliedMode != GroundSurfaceMode.SURFACE_MODEL) {
            canopy.indices.forEach { index ->
                canopy[index] = if (allCount[index] > 0 && allMax[index].isFinite()) {
                    (allMax[index] - smoothed[index]).coerceAtLeast(0f)
                } else 0f
            }
        }

        val geographic = header.minX in -180.0..180.0 && header.maxX in -180.0..180.0 &&
            header.minY in -90.0..90.0 && header.maxY in -90.0..90.0
        val bounds = if (geographic) DemBounds(header.minY, header.minX, header.maxY, header.maxX) else null
        val cellSize = if (bounds != null) {
            TerrainRaster.geographicCellSize(bounds, width, height)
        } else {
            (rangeX / (width - 1).coerceAtLeast(1)).coerceAtLeast(0.001) to
                (rangeY / (height - 1).coerceAtLeast(1)).coerceAtLeast(0.001)
        }
        val warning = when {
            decoded >= MAX_DECODED_POINTS -> "Mobile safety limit reached; the point cloud was uniformly sampled."
            sampleStride > 1 -> "Large point cloud; every ${sampleStride}th return was rasterized."
            options.groundMode == GroundSurfaceMode.SOURCE_CLASSIFIED && !usableGroundClasses ->
                "Ground-class coverage was sparse; automatic lowest-return filtering was used."
            !geographic -> "Projected coordinates shown locally; assign/reproject CRS before map overlay."
            else -> null
        }
        return TerrainRaster(
            width = width,
            height = height,
            elevations = smoothed,
            cellWidthM = cellSize.first,
            cellHeightM = cellSize.second,
            name = name,
            geoBounds = bounds,
            canopyHeights = canopy,
            metadata = TerrainRasterMetadata(
                source = "LAS/LAZ ${header.version} format ${header.pointFormat}",
                pointCount = decoded,
                groundPointCount = ground,
                classHistogram = histogram.withIndex().filter { it.value > 0L }.associate { it.index to it.value },
                groundMode = appliedMode.label,
                coordinateSystem = if (geographic) "WGS84-like geographic" else "Projected/local",
                warning = warning,
            ),
        )
    }

    companion object {
        private const val MIN_SHORT_SIDE = 64
        private const val MAX_BINNED_POINTS = 8_000_000.0
        private const val MAX_DECODED_POINTS = 30_000_000L
    }
}

private fun suppressLowOutliers(source: FloatArray, width: Int, height: Int): FloatArray {
    val output = source.copyOf()
    val neighbors = FloatArray(8)
    for (row in 1 until height - 1) for (column in 1 until width - 1) {
        var count = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            neighbors[count++] = source[(row + dy) * width + column + dx]
        }
        neighbors.sort(0, count)
        val median = neighbors[count / 2]
        val index = row * width + column
        if (source[index] < median - 3f) output[index] = median
    }
    return output
}

private fun boxSmooth(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
    val stride = width + 1
    val integral = DoubleArray((width + 1) * (height + 1))
    for (row in 0 until height) {
        var rowSum = 0.0
        for (column in 0 until width) {
            rowSum += source[row * width + column]
            integral[(row + 1) * stride + column + 1] = integral[row * stride + column + 1] + rowSum
        }
    }
    return FloatArray(source.size) { index ->
        val row = index / width
        val column = index % width
        val x0 = (column - radius).coerceAtLeast(0)
        val x1 = (column + radius).coerceAtMost(width - 1)
        val y0 = (row - radius).coerceAtLeast(0)
        val y1 = (row + radius).coerceAtMost(height - 1)
        val sum = integral[(y1 + 1) * stride + x1 + 1] - integral[y0 * stride + x1 + 1] -
            integral[(y1 + 1) * stride + x0] + integral[y0 * stride + x0]
        (sum / ((x1 - x0 + 1) * (y1 - y0 + 1))).toFloat()
    }
}
