package com.viewshed.app.viewshed.terrain

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class TerrainRenderMode(val label: String) {
    HILLSHADE("Hillshade"),
    MULTI_HILLSHADE("Multi-direction hillshade"),
    ELEVATION("Elevation color"),
    SLOPE("Slope"),
    ASPECT("Aspect"),
    LOCAL_RELIEF("Local relief model"),
    OPENNESS("Openness"),
    SKY_VIEW("Sky-view factor"),
    CANOPY("Canopy / structures"),
    ANOMALY("Ground disturbance"),
}

data class TerrainRenderOptions(
    val mode: TerrainRenderMode = TerrainRenderMode.HILLSHADE,
    val lightAzimuthDeg: Double = 315.0,
    val lightAltitudeDeg: Double = 35.0,
    val verticalExaggeration: Double = 1.0,
    val neighborhoodRadius: Int = 8,
)

data class TerrainFeatureCandidate(
    val type: String,
    val centerRow: Double,
    val centerColumn: Double,
    val areaM2: Double,
    val reliefM: Double,
    val confidence: Double,
    val minRow: Int,
    val minColumn: Int,
    val maxRow: Int,
    val maxColumn: Int,
)

data class TerrainProfilePoint(val distanceM: Double, val elevationM: Double)
data class ContourSegment(val levelM: Double, val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/** Pure CPU terrain products. Rendering work is dispatched off the UI thread by the activity. */
object TerrainAnalysis {
    fun render(raster: TerrainRaster, options: TerrainRenderOptions): Bitmap {
        val width = raster.width
        val height = raster.height
        val pixels = when (options.mode) {
            TerrainRenderMode.HILLSHADE -> hillshade(raster, options.lightAzimuthDeg, options.lightAltitudeDeg, options.verticalExaggeration)
            TerrainRenderMode.MULTI_HILLSHADE -> multidirectionalHillshade(raster, options.lightAltitudeDeg, options.verticalExaggeration)
            TerrainRenderMode.ELEVATION -> elevationColors(raster)
            TerrainRenderMode.SLOPE -> slopeColors(raster, options.verticalExaggeration)
            TerrainRenderMode.ASPECT -> aspectColors(raster, options.verticalExaggeration)
            TerrainRenderMode.LOCAL_RELIEF -> localReliefColors(raster, options.neighborhoodRadius)
            TerrainRenderMode.OPENNESS -> opennessColors(raster, options.neighborhoodRadius)
            TerrainRenderMode.SKY_VIEW -> skyViewColors(raster, options.neighborhoodRadius)
            TerrainRenderMode.CANOPY -> canopyColors(raster)
            TerrainRenderMode.ANOMALY -> anomalyColors(raster, options.neighborhoodRadius)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun contours(raster: TerrainRaster, intervalM: Double, maxSegments: Int = 40_000): List<ContourSegment> {
        if (intervalM <= 0.0) return emptyList()
        val output = ArrayList<ContourSegment>()
        val start = floor(raster.minElevationM / intervalM) * intervalM
        var level = start
        while (level <= raster.maxElevationM && output.size < maxSegments) {
            for (row in 0 until raster.height - 1) for (column in 0 until raster.width - 1) {
                val values = doubleArrayOf(
                    raster.elevations[row * raster.width + column].toDouble(),
                    raster.elevations[row * raster.width + column + 1].toDouble(),
                    raster.elevations[(row + 1) * raster.width + column + 1].toDouble(),
                    raster.elevations[(row + 1) * raster.width + column].toDouble(),
                )
                val corners = arrayOf(
                    column.toFloat() to row.toFloat(),
                    (column + 1).toFloat() to row.toFloat(),
                    (column + 1).toFloat() to (row + 1).toFloat(),
                    column.toFloat() to (row + 1).toFloat(),
                )
                val hits = ArrayList<Pair<Float, Float>>(4)
                for (edge in 0..3) {
                    val next = (edge + 1) % 4
                    val a = values[edge]
                    val b = values[next]
                    if ((a <= level && b > level) || (a > level && b <= level)) {
                        val t = ((level - a) / (b - a)).coerceIn(0.0, 1.0).toFloat()
                        hits += (corners[edge].first + (corners[next].first - corners[edge].first) * t) to
                            (corners[edge].second + (corners[next].second - corners[edge].second) * t)
                    }
                }
                if (hits.size >= 2) output += ContourSegment(level, hits[0].first, hits[0].second, hits[1].first, hits[1].second)
                if (hits.size == 4) output += ContourSegment(level, hits[2].first, hits[2].second, hits[3].first, hits[3].second)
                if (output.size >= maxSegments) break
            }
            level += intervalM
        }
        return output
    }

    fun profile(
        raster: TerrainRaster,
        startRow: Double,
        startColumn: Double,
        endRow: Double,
        endColumn: Double,
        samples: Int = 256,
    ): List<TerrainProfilePoint> {
        val count = samples.coerceIn(2, 2_048)
        val totalDistance = raster.horizontalDistanceM(startRow, startColumn, endRow, endColumn)
        return (0 until count).map { index ->
            val fraction = index.toDouble() / (count - 1)
            val row = startRow + (endRow - startRow) * fraction
            val column = startColumn + (endColumn - startColumn) * fraction
            TerrainProfilePoint(totalDistance * fraction, bilinear(raster, row, column))
        }
    }

    /** Connected local-relief anomalies ranked for cellar holes, pads, ditches, and mounds. */
    fun detectFeatures(
        raster: TerrainRaster,
        thresholdM: Double = 0.45,
        radius: Int = 8,
        maxCandidates: Int = 200,
    ): List<TerrainFeatureCandidate> {
        val residual = localResidual(raster, radius.coerceIn(2, 32))
        val visited = BooleanArray(residual.size)
        val candidates = mutableListOf<TerrainFeatureCandidate>()
        val minCells = max(3, (4.0 / (raster.cellWidthM * raster.cellHeightM)).toInt())
        val maxCells = max(minCells + 1, (25_000.0 / (raster.cellWidthM * raster.cellHeightM)).toInt())
        val queue = IntArray(residual.size)
        for (seed in residual.indices) {
            if (visited[seed] || abs(residual[seed]) < thresholdM) continue
            val sign = if (residual[seed] < 0) -1 else 1
            var head = 0
            var tail = 0
            queue[tail++] = seed
            visited[seed] = true
            var cells = 0
            var rowSum = 0.0
            var columnSum = 0.0
            var reliefSum = 0.0
            var reliefMax = 0.0
            var minRow = Int.MAX_VALUE
            var minColumn = Int.MAX_VALUE
            var maxRow = Int.MIN_VALUE
            var maxColumn = Int.MIN_VALUE
            while (head < tail && cells <= maxCells) {
                val index = queue[head++]
                val row = index / raster.width
                val column = index % raster.width
                val value = residual[index]
                cells++
                rowSum += row
                columnSum += column
                reliefSum += abs(value)
                reliefMax = max(reliefMax, abs(value))
                minRow = min(minRow, row)
                maxRow = max(maxRow, row)
                minColumn = min(minColumn, column)
                maxColumn = max(maxColumn, column)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val rr = row + dy
                    val cc = column + dx
                    if (rr !in 0 until raster.height || cc !in 0 until raster.width) continue
                    val next = rr * raster.width + cc
                    if (!visited[next] && abs(residual[next]) >= thresholdM &&
                        (if (residual[next] < 0) -1 else 1) == sign
                    ) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }
            if (cells !in minCells..maxCells) continue
            val boxWidth = maxColumn - minColumn + 1
            val boxHeight = maxRow - minRow + 1
            val boxCells = boxWidth * boxHeight
            val fillRatio = cells.toDouble() / boxCells.coerceAtLeast(1)
            val aspectRatio = max(boxWidth, boxHeight).toDouble() / min(boxWidth, boxHeight).coerceAtLeast(1)
            val meanRelief = reliefSum / cells
            val rectangular = fillRatio in 0.28..0.92 && aspectRatio in 1.0..4.5
            val linear = aspectRatio > 4.5
            val type = when {
                rectangular && meanRelief < 2.5 -> if (sign < 0) "Possible foundation/cellar" else "Possible raised pad"
                linear -> if (sign < 0) "Ditch/track" else "Bank/wall"
                sign < 0 -> "Depression"
                else -> "Mound"
            }
            val shapeScore = when {
                rectangular -> 0.9
                linear -> 0.75
                else -> 0.55
            }
            val reliefScore = (meanRelief / (thresholdM * 3.0)).coerceIn(0.0, 1.0)
            candidates += TerrainFeatureCandidate(
                type = type,
                centerRow = rowSum / cells,
                centerColumn = columnSum / cells,
                areaM2 = cells * raster.cellWidthM * raster.cellHeightM,
                reliefM = reliefMax,
                confidence = (0.35 + 0.4 * shapeScore + 0.25 * reliefScore).coerceIn(0.0, 0.99),
                minRow = minRow,
                minColumn = minColumn,
                maxRow = maxRow,
                maxColumn = maxColumn,
            )
        }
        return candidates.sortedWith(compareByDescending<TerrainFeatureCandidate> { it.confidence }.thenByDescending { it.reliefM })
            .take(maxCandidates)
    }

    private fun hillshade(raster: TerrainRaster, azimuth: Double, altitude: Double, exaggeration: Double): IntArray {
        val pixels = IntArray(raster.elevations.size)
        val azimuthRad = Math.toRadians(360.0 - azimuth + 90.0)
        val altitudeRad = Math.toRadians(altitude.coerceIn(1.0, 89.0))
        for (row in 0 until raster.height) for (column in 0 until raster.width) {
            val gradient = gradient(raster, row, column, exaggeration)
            val slope = atan(hypot(gradient.first, gradient.second))
            var aspect = atan2(gradient.second, -gradient.first)
            if (aspect < 0) aspect += 2 * PI
            val shade = (sin(altitudeRad) * cos(slope) +
                cos(altitudeRad) * sin(slope) * cos(azimuthRad - aspect)).coerceIn(0.0, 1.0)
            val gray = (shade * 255).toInt()
            pixels[row * raster.width + column] = Color.rgb(gray, gray, gray)
        }
        return pixels
    }

    private fun multidirectionalHillshade(raster: TerrainRaster, altitude: Double, exaggeration: Double): IntArray {
        val directions = doubleArrayOf(225.0, 270.0, 315.0, 360.0)
        val layers = directions.map { hillshade(raster, it, altitude, exaggeration) }
        return IntArray(raster.elevations.size) { index ->
            val gray = layers.sumOf { Color.red(it[index]) } / layers.size
            Color.rgb(gray, gray, gray)
        }
    }

    private fun elevationColors(raster: TerrainRaster): IntArray {
        val range = (raster.maxElevationM - raster.minElevationM).coerceAtLeast(0.001f)
        return IntArray(raster.elevations.size) { index ->
            val t = ((raster.elevations[index] - raster.minElevationM) / range).coerceIn(0f, 1f)
            when {
                t < 0.35f -> lerpColor(Color.rgb(28, 84, 64), Color.rgb(126, 145, 83), t / 0.35f)
                t < 0.7f -> lerpColor(Color.rgb(126, 145, 83), Color.rgb(168, 129, 85), (t - 0.35f) / 0.35f)
                else -> lerpColor(Color.rgb(168, 129, 85), Color.rgb(245, 245, 240), (t - 0.7f) / 0.3f)
            }
        }
    }

    private fun slopeColors(raster: TerrainRaster, exaggeration: Double): IntArray = IntArray(raster.elevations.size) { index ->
        val row = index / raster.width
        val column = index % raster.width
        val g = gradient(raster, row, column, exaggeration)
        val degrees = Math.toDegrees(atan(hypot(g.first, g.second))).coerceIn(0.0, 60.0)
        val t = (degrees / 60.0).toFloat()
        lerpColor(Color.rgb(248, 247, 220), Color.rgb(151, 30, 45), t)
    }

    private fun aspectColors(raster: TerrainRaster, exaggeration: Double): IntArray = IntArray(raster.elevations.size) { index ->
        val row = index / raster.width
        val column = index % raster.width
        val g = gradient(raster, row, column, exaggeration)
        val bearing = (Math.toDegrees(atan2(g.first, -g.second)) + 360.0) % 360.0
        Color.HSVToColor(floatArrayOf(bearing.toFloat(), 0.72f, 0.92f))
    }

    private fun localReliefColors(raster: TerrainRaster, radius: Int): IntArray {
        val residual = localResidual(raster, radius)
        val scale = residual.map(::abs).sorted().let { values -> values[(values.size * 0.98).toInt().coerceIn(0, values.lastIndex)] }
            .coerceAtLeast(0.1)
        return IntArray(residual.size) { index -> divergingColor((residual[index] / scale).coerceIn(-1.0, 1.0)) }
    }

    private fun anomalyColors(raster: TerrainRaster, radius: Int): IntArray {
        val residual = localResidual(raster, radius)
        val scale = residual.map(::abs).sorted().let { values -> values[(values.size * 0.95).toInt().coerceIn(0, values.lastIndex)] }
            .coerceAtLeast(0.1)
        return IntArray(residual.size) { index ->
            val t = (residual[index] / scale).coerceIn(-1.0, 1.0)
            when {
                t < -0.15 -> lerpColor(Color.rgb(18, 42, 76), Color.rgb(44, 150, 196), ((t + 1.0) / 0.85).toFloat())
                t > 0.15 -> lerpColor(Color.rgb(237, 187, 61), Color.rgb(128, 35, 32), ((t - 0.15) / 0.85).toFloat())
                else -> Color.rgb(226, 224, 215)
            }
        }
    }

    private fun opennessColors(raster: TerrainRaster, radius: Int): IntArray {
        val r = radius.coerceIn(2, 24)
        return IntArray(raster.elevations.size) { index ->
            val row = index / raster.width
            val column = index % raster.width
            val center = raster.elevations[index]
            var angleSum = 0.0
            var count = 0
            for ((dx, dy) in DIRECTIONS) {
                val rr = (row + dy * r).coerceIn(0, raster.height - 1)
                val cc = (column + dx * r).coerceIn(0, raster.width - 1)
                val dz = raster.elevations[rr * raster.width + cc] - center
                val distance = hypot(dx * r * raster.cellWidthM, dy * r * raster.cellHeightM).coerceAtLeast(0.01)
                angleSum += atan(dz / distance)
                count++
            }
            val openness = (0.5 - angleSum / count / PI).coerceIn(0.0, 1.0)
            val gray = (openness * 255).toInt()
            Color.rgb(gray, gray, gray)
        }
    }

    private fun skyViewColors(raster: TerrainRaster, radius: Int): IntArray {
        val r = radius.coerceIn(2, 24)
        return IntArray(raster.elevations.size) { index ->
            val row = index / raster.width
            val column = index % raster.width
            val center = raster.elevations[index]
            var visible = 0.0
            for ((dx, dy) in DIRECTIONS) {
                var horizon = Double.NEGATIVE_INFINITY
                for (step in 1..r) {
                    val rr = row + dy * step
                    val cc = column + dx * step
                    if (rr !in 0 until raster.height || cc !in 0 until raster.width) break
                    val dz = raster.elevations[rr * raster.width + cc] - center
                    val distance = hypot(dx * step * raster.cellWidthM, dy * step * raster.cellHeightM)
                    horizon = max(horizon, atan2(dz.toDouble(), distance))
                }
                visible += cos(horizon.coerceAtLeast(0.0)).let { it * it }
            }
            val factor = (visible / DIRECTIONS.size).coerceIn(0.0, 1.0)
            val gray = (factor * 255).toInt()
            Color.rgb(gray, gray, gray)
        }
    }

    private fun canopyColors(raster: TerrainRaster): IntArray {
        val canopy = raster.canopyHeights ?: FloatArray(raster.elevations.size)
        val maxValue = canopy.sortedArray().let { it[(it.size * 0.98).toInt().coerceIn(0, it.lastIndex)] }.coerceAtLeast(1f)
        return IntArray(canopy.size) { index ->
            val t = (canopy[index] / maxValue).coerceIn(0f, 1f)
            lerpColor(Color.rgb(242, 238, 218), Color.rgb(28, 112, 63), t)
        }
    }

    private fun localResidual(raster: TerrainRaster, radius: Int): DoubleArray {
        val r = radius.coerceIn(1, 64)
        val width = raster.width
        val height = raster.height
        val stride = width + 1
        val integral = DoubleArray((width + 1) * (height + 1))
        for (row in 0 until height) {
            var rowSum = 0.0
            for (column in 0 until width) {
                rowSum += raster.elevations[row * width + column]
                integral[(row + 1) * stride + column + 1] = integral[row * stride + column + 1] + rowSum
            }
        }
        return DoubleArray(width * height) { index ->
            val row = index / width
            val column = index % width
            val x0 = (column - r).coerceAtLeast(0)
            val x1 = (column + r).coerceAtMost(width - 1)
            val y0 = (row - r).coerceAtLeast(0)
            val y1 = (row + r).coerceAtMost(height - 1)
            val sum = integral[(y1 + 1) * stride + x1 + 1] - integral[y0 * stride + x1 + 1] -
                integral[(y1 + 1) * stride + x0] + integral[y0 * stride + x0]
            raster.elevations[index] - sum / ((x1 - x0 + 1) * (y1 - y0 + 1))
        }
    }

    private fun gradient(raster: TerrainRaster, row: Int, column: Int, exaggeration: Double): Pair<Double, Double> {
        val left = raster.elevations[row * raster.width + (column - 1).coerceAtLeast(0)]
        val right = raster.elevations[row * raster.width + (column + 1).coerceAtMost(raster.width - 1)]
        val up = raster.elevations[(row - 1).coerceAtLeast(0) * raster.width + column]
        val down = raster.elevations[(row + 1).coerceAtMost(raster.height - 1) * raster.width + column]
        return ((right - left) * exaggeration / (2.0 * raster.cellWidthM)) to
            ((down - up) * exaggeration / (2.0 * raster.cellHeightM))
    }

    private fun bilinear(raster: TerrainRaster, row: Double, column: Double): Double {
        val r0 = floor(row).toInt().coerceIn(0, raster.height - 1)
        val c0 = floor(column).toInt().coerceIn(0, raster.width - 1)
        val r1 = (r0 + 1).coerceAtMost(raster.height - 1)
        val c1 = (c0 + 1).coerceAtMost(raster.width - 1)
        val tx = (column - c0).coerceIn(0.0, 1.0)
        val ty = (row - r0).coerceIn(0.0, 1.0)
        val top = raster.elevations[r0 * raster.width + c0] * (1 - tx) + raster.elevations[r0 * raster.width + c1] * tx
        val bottom = raster.elevations[r1 * raster.width + c0] * (1 - tx) + raster.elevations[r1 * raster.width + c1] * tx
        return top * (1 - ty) + bottom * ty
    }

    private fun divergingColor(value: Double): Int = when {
        value < 0 -> lerpColor(Color.rgb(34, 98, 168), Color.WHITE, (value + 1.0).toFloat())
        else -> lerpColor(Color.WHITE, Color.rgb(181, 56, 42), value.toFloat())
    }

    private fun lerpColor(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
        )
    }

    private val DIRECTIONS = arrayOf(
        1 to 0, 1 to 1, 0 to 1, -1 to 1,
        -1 to 0, -1 to -1, 0 to -1, 1 to -1,
    )
}
