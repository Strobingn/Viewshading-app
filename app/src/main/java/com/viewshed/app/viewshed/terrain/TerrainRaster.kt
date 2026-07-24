package com.viewshed.app.viewshed.terrain

import com.viewshed.app.viewshed.DemBounds
import com.viewshed.app.viewshed.GeoMath
import com.viewshed.app.viewshed.GeoPoint
import com.viewshed.app.viewshed.RasterDemSource
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

/**
 * Memory-bounded terrain image shared by the 2D/3D renderer and LiDAR analysis tools.
 * Row zero is north/top. Cell dimensions are always supplied in meters so slope,
 * profile, area, and disturbance calculations remain meaningful for geographic DEMs.
 */
data class TerrainRaster(
    val width: Int,
    val height: Int,
    val elevations: FloatArray,
    val cellWidthM: Double,
    val cellHeightM: Double,
    val name: String,
    val geoBounds: DemBounds? = null,
    val canopyHeights: FloatArray? = null,
    val metadata: TerrainRasterMetadata = TerrainRasterMetadata(),
) {
    init {
        require(width >= 2 && height >= 2) { "Terrain raster must be at least 2×2" }
        require(elevations.size == width * height)
        require(cellWidthM > 0.0 && cellHeightM > 0.0)
        require(canopyHeights == null || canopyHeights.size == elevations.size)
    }

    val minElevationM: Float by lazy { elevations.filterFiniteMin() }
    val maxElevationM: Float by lazy { elevations.filterFiniteMax() }
    val meanElevationM: Double by lazy {
        var sum = 0.0
        var count = 0
        elevations.forEach { value ->
            if (value.isFinite()) {
                sum += value
                count++
            }
        }
        if (count == 0) 0.0 else sum / count
    }

    fun elevationAt(row: Int, column: Int): Float? {
        if (row !in 0 until height || column !in 0 until width) return null
        return elevations[row * width + column].takeIf(Float::isFinite)
    }

    fun indexToGeo(row: Double, column: Double): GeoPoint? {
        val bounds = geoBounds ?: return null
        val x = (column / (width - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val y = (row / (height - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return GeoPoint(
            lat = bounds.north + (bounds.south - bounds.north) * y,
            lon = bounds.west + (bounds.east - bounds.west) * x,
        )
    }

    fun horizontalDistanceM(aRow: Double, aColumn: Double, bRow: Double, bColumn: Double): Double {
        val a = indexToGeo(aRow, aColumn)
        val b = indexToGeo(bRow, bColumn)
        if (a != null && b != null) return GeoMath.distanceM(a, b)
        val dx = (bColumn - aColumn) * cellWidthM
        val dy = (bRow - aRow) * cellHeightM
        return kotlin.math.hypot(dx, dy)
    }

    companion object {
        /** Convert an existing regular grid while limiting the rendered working set. */
        fun from(grid: TerrainGrid, maxDimension: Int = 1_536): TerrainRaster {
            val step = ceil(max(grid.ncols, grid.nrows).toDouble() / maxDimension).toInt().coerceAtLeast(1)
            val width = ((grid.ncols - 1) / step + 1).coerceAtLeast(2)
            val height = ((grid.nrows - 1) / step + 1).coerceAtLeast(2)
            val output = FloatArray(width * height)
            for (row in 0 until height) {
                val sourceRow = (row * step).coerceAtMost(grid.nrows - 1)
                for (column in 0 until width) {
                    val sourceColumn = (column * step).coerceAtMost(grid.ncols - 1)
                    output[row * width + column] = grid.elevationAt(sourceRow, sourceColumn) ?: Float.NaN
                }
            }
            fillMissingTerrain(output, width, height)
            val meters = grid.cellSizeMetersApprox()
            return TerrainRaster(
                width = width,
                height = height,
                elevations = output,
                cellWidthM = meters.first * step,
                cellHeightM = meters.second * step,
                name = grid.name,
                geoBounds = DemBounds(grid.south, grid.west, grid.north, grid.east),
                metadata = TerrainRasterMetadata(source = "Regular DEM", downsampleStep = step),
            )
        }

        /** Convert a GeoTIFF/ASCII raster without retaining an additional full-size copy. */
        fun from(source: RasterDemSource, name: String, maxDimension: Int = 1_536): TerrainRaster {
            val step = ceil(max(source.columns, source.rows).toDouble() / maxDimension).toInt().coerceAtLeast(1)
            val width = ((source.columns - 1) / step + 1).coerceAtLeast(2)
            val height = ((source.rows - 1) / step + 1).coerceAtLeast(2)
            val output = FloatArray(width * height)
            for (row in 0 until height) {
                val sourceRow = (row * step).coerceAtMost(source.rows - 1)
                for (column in 0 until width) {
                    val sourceColumn = (column * step).coerceAtMost(source.columns - 1)
                    output[row * width + column] =
                        source.elevationAt(sourceRow, sourceColumn)?.toFloat() ?: Float.NaN
                }
            }
            fillMissingTerrain(output, width, height)

            val topLeft = source.pointAt(0, 0)
            val right = source.pointAt(0, step.coerceAtMost(source.columns - 1))
            val down = source.pointAt(step.coerceAtMost(source.rows - 1), 0)
            val widthM = GeoMath.distanceM(topLeft, right).coerceAtLeast(0.01)
            val heightM = GeoMath.distanceM(topLeft, down).coerceAtLeast(0.01)
            return TerrainRaster(
                width = width,
                height = height,
                elevations = output,
                cellWidthM = widthM,
                cellHeightM = heightM,
                name = name,
                geoBounds = source.bounds,
                metadata = TerrainRasterMetadata(source = "GeoTIFF/ASCII", downsampleStep = step),
            )
        }

        fun geographicCellSize(bounds: DemBounds, width: Int, height: Int): Pair<Double, Double> {
            val midLatitude = (bounds.north + bounds.south) / 2.0
            val metersPerLon = 111_320.0 * cos(Math.toRadians(midLatitude)).coerceAtLeast(0.1)
            return ((bounds.east - bounds.west) * metersPerLon / (width - 1).coerceAtLeast(1)) to
                ((bounds.north - bounds.south) * 111_320.0 / (height - 1).coerceAtLeast(1))
        }
    }
}

data class TerrainRasterMetadata(
    val source: String = "Unknown",
    val pointCount: Long = 0,
    val groundPointCount: Long = 0,
    val classHistogram: Map<Int, Long> = emptyMap(),
    val groundMode: String = "",
    val coordinateSystem: String = "",
    val downsampleStep: Int = 1,
    val warning: String? = null,
)

internal fun fillMissingTerrain(values: FloatArray, width: Int, height: Int) {
    if (values.none(Float::isFinite)) {
        values.fill(0f)
        return
    }
    // Alternating directional propagation fills even broad nodata areas in O(n).
    repeat(4) { pass ->
        val rows = if (pass % 2 == 0) 0 until height else height - 1 downTo 0
        val columns = if (pass % 2 == 0) 0 until width else width - 1 downTo 0
        for (row in rows) for (column in columns) {
            val index = row * width + column
            if (values[index].isFinite()) continue
            val neighbors = intArrayOf(
                if (column > 0) index - 1 else -1,
                if (column + 1 < width) index + 1 else -1,
                if (row > 0) index - width else -1,
                if (row + 1 < height) index + width else -1,
            )
            val replacement = neighbors.firstOrNull { it >= 0 && values[it].isFinite() }
            if (replacement != null) values[index] = values[replacement]
        }
    }
    val fallback = values.firstOrNull(Float::isFinite) ?: 0f
    values.indices.forEach { if (!values[it].isFinite()) values[it] = fallback }
}

private fun FloatArray.filterFiniteMin(): Float {
    var minimum = Float.POSITIVE_INFINITY
    forEach { if (it.isFinite() && it < minimum) minimum = it }
    return minimum.takeIf(Float::isFinite) ?: 0f
}

private fun FloatArray.filterFiniteMax(): Float {
    var maximum = Float.NEGATIVE_INFINITY
    forEach { if (it.isFinite() && it > maximum) maximum = it }
    return maximum.takeIf(Float::isFinite) ?: 0f
}
