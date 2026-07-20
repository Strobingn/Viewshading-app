package com.viewshed.app.viewshed.terrain

import com.viewshed.app.viewshed.GeoPoint
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Regular-grid digital elevation model (local terrain surface).
 *
 * Cells are row-major: index = row * ncols + col.
 * Geographic axes: [south, north] × [west, east] (lat/lon degrees).
 * Supports bilinear sampling for ViewshedEngine ray marches.
 */
data class TerrainGrid(
    val ncols: Int,
    val nrows: Int,
    /** West edge (min longitude), degrees. */
    val west: Double,
    /** South edge (min latitude), degrees. */
    val south: Double,
    /** Cell width in degrees longitude. */
    val cellSizeLon: Double,
    /** Cell height in degrees latitude. */
    val cellSizeLat: Double,
    /** Elevations in meters; size = ncols * nrows. */
    val elevations: FloatArray,
    val nodata: Float = -9999f,
    val name: String = "terrain",
) {
    init {
        require(ncols > 0 && nrows > 0)
        require(elevations.size == ncols * nrows) {
            "elevations size ${elevations.size} != ${ncols * nrows}"
        }
        require(cellSizeLon > 0 && cellSizeLat > 0)
    }

    val east: Double get() = west + ncols * cellSizeLon
    val north: Double get() = south + nrows * cellSizeLat

    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east

    fun index(row: Int, col: Int): Int = row * ncols + col

    fun elevationAt(row: Int, col: Int): Float? {
        if (row !in 0 until nrows || col !in 0 until ncols) return null
        val z = elevations[index(row, col)]
        return if (z == nodata || z.isNaN()) null else z
    }

    /**
     * Bilinear interpolation in geographic degrees.
     * @return meters, or null if outside grid / nodata neighborhood.
     */
    fun sampleBilinear(lat: Double, lon: Double): Double? {
        if (!contains(lat, lon)) return null
        val fc = ((lon - west) / cellSizeLon).toFloat()
        // row 0 is north edge (ASC convention) or south? We store row 0 = north (ASC)
        // ASC: first data row is northernmost. y decreases as row increases.
        val fr = ((north - lat) / cellSizeLat).toFloat()
        val c0 = floor(fc.toDouble()).toInt()
        val r0 = floor(fr.toDouble()).toInt()
        val c1 = min(c0 + 1, ncols - 1)
        val r1 = min(r0 + 1, nrows - 1)
        if (r0 !in 0 until nrows || c0 !in 0 until ncols) return null

        val tx = (fc - c0).toDouble().coerceIn(0.0, 1.0)
        val ty = (fr - r0).toDouble().coerceIn(0.0, 1.0)

        val z00 = elevationAt(r0, c0) ?: return nearestValid(lat, lon)
        val z10 = elevationAt(r0, c1) ?: z00
        val z01 = elevationAt(r1, c0) ?: z00
        val z11 = elevationAt(r1, c1) ?: z10

        val z0 = z00 + (z10 - z00) * tx
        val z1 = z01 + (z11 - z01) * tx
        return z0 + (z1 - z0) * ty
    }

    fun sampleOrFallback(point: GeoPoint, fallback: (GeoPoint) -> Double): Double =
        sampleBilinear(point.lat, point.lon) ?: fallback(point)

    /** Nearest non-nodata cell (meters). */
    fun nearestValid(lat: Double, lon: Double, maxCells: Int = 8): Double? {
        val fc = ((lon - west) / cellSizeLon)
        val fr = ((north - lat) / cellSizeLat)
        val cCenter = fc.toInt().coerceIn(0, ncols - 1)
        val rCenter = fr.toInt().coerceIn(0, nrows - 1)
        var bestD = Double.MAX_VALUE
        var bestZ: Float? = null
        for (dr in -maxCells..maxCells) {
            for (dc in -maxCells..maxCells) {
                val r = rCenter + dr
                val c = cCenter + dc
                val z = elevationAt(r, c) ?: continue
                val d = dr.toDouble() * dr + dc.toDouble() * dc
                if (d < bestD) {
                    bestD = d
                    bestZ = z
                }
            }
        }
        return bestZ?.toDouble()
    }

    /**
     * Approximate cell size in meters at grid center (for hillshade).
     */
    fun cellSizeMetersApprox(): Pair<Double, Double> {
        val midLat = (south + north) / 2.0
        val mLat = cellSizeLat * 111_320.0
        val mLon = cellSizeLon * 111_320.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
        return mLon to mLat
    }

    fun stats(): TerrainStats {
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var sum = 0.0
        var n = 0
        for (z in elevations) {
            if (z == nodata || z.isNaN()) continue
            minZ = min(minZ, z)
            maxZ = max(maxZ, z)
            sum += z
            n++
        }
        return TerrainStats(
            validCells = n,
            minElevM = if (n > 0) minZ.toDouble() else 0.0,
            maxElevM = if (n > 0) maxZ.toDouble() else 0.0,
            meanElevM = if (n > 0) sum / n else 0.0,
        )
    }
}

data class TerrainStats(
    val validCells: Int,
    val minElevM: Double,
    val maxElevM: Double,
    val meanElevM: Double,
)
