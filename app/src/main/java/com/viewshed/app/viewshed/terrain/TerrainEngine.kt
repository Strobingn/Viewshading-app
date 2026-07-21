package com.viewshed.app.viewshed.terrain

import android.graphics.Bitmap
import android.graphics.Color
import com.viewshed.app.viewshed.DemoTerrain
import com.viewshed.app.viewshed.ElevationGrid
import com.viewshed.app.viewshed.GeoPoint
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local DEM terrain engine for Viewshade (README: GeoTIFF/CSV stub → real ASC/CSV).
 *
 * - Load ESRI ASCII Grid (.asc / .grd)
 * - Load simple CSV: lat,lon,elev or lon,lat,elev with header
 * - Synthesize demo region for offline Newburgh-area testing
 * - Bilinear sampling into [ElevationGrid] for [com.viewshed.app.viewshed.ViewshedEngine]
 * - Hillshade raster for terrain visualization
 */
object TerrainEngine {

    /**
     * Load ESRI ASCII Grid.
     * Header keys: ncols, nrows, xllcorner|xllcenter, yllcorner|yllcenter, cellsize|dx/dy, NODATA_value.
     */
    fun loadEsriAscii(stream: InputStream, name: String = "dem.asc"): TerrainGrid {
        val reader = BufferedReader(InputStreamReader(stream))
        var ncols = 0
        var nrows = 0
        var xll: Double? = null
        var yll: Double? = null
        var xllCenter = false
        var yllCenter = false
        var cellSize: Double? = null
        var dx: Double? = null
        var dy: Double? = null
        var nodata = -9999.0

        val dataLines = ArrayList<String>()
        var inData = false
        reader.useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (!inData) {
                    val parts = line.split(Regex("\\s+"))
                    val key = parts[0].lowercase(Locale.US)
                    val value = parts.getOrNull(1) ?: continue
                    when (key) {
                        "ncols" -> ncols = value.toInt()
                        "nrows" -> nrows = value.toInt()
                        "xllcorner" -> {
                            xll = value.toDouble()
                            xllCenter = false
                        }
                        "yllcorner" -> {
                            yll = value.toDouble()
                            yllCenter = false
                        }
                        "xllcenter" -> {
                            xll = value.toDouble()
                            xllCenter = true
                        }
                        "yllcenter" -> {
                            yll = value.toDouble()
                            yllCenter = true
                        }
                        "cellsize" -> cellSize = value.toDouble()
                        "dx" -> dx = value.toDouble()
                        "dy" -> dy = value.toDouble()
                        "nodata_value" -> nodata = value.toDouble()
                        else -> {
                            // first numeric data row
                            if (parts[0].toDoubleOrNull() != null || parts[0].startsWith("-")) {
                                inData = true
                                dataLines.add(line)
                            }
                        }
                    }
                } else {
                    dataLines.add(line)
                }
            }
        }

        require(ncols > 0 && nrows > 0) { "Invalid ASC header: ncols/nrows" }
        val csLon = dx ?: cellSize ?: error("ASC missing cellsize")
        val csLat = dy ?: cellSize ?: csLon
        var west = xll ?: error("ASC missing xllcorner")
        var south = yll ?: error("ASC missing yllcorner")
        if (xllCenter) west -= csLon / 2
        if (yllCenter) south -= csLat / 2

        val elev = FloatArray(ncols * nrows)
        var row = 0
        for (line in dataLines) {
            if (row >= nrows) break
            val vals = line.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
            if (vals.isEmpty()) continue
            for (col in 0 until minOf(ncols, vals.size)) {
                elev[row * ncols + col] = vals[col]
            }
            // ASC: first row is north
            row++
        }
        require(row >= nrows / 2) { "ASC data incomplete: got $row rows, expected $nrows" }

        return TerrainGrid(
            ncols = ncols,
            nrows = nrows,
            west = west,
            south = south,
            cellSizeLon = csLon,
            cellSizeLat = csLat,
            elevations = elev,
            nodata = nodata.toFloat(),
            name = name,
        )
    }

    fun loadEsriAscii(file: File): TerrainGrid =
        file.inputStream().use { loadEsriAscii(it, file.name) }

    /**
     * CSV with header containing lat/lon/elev (flexible column names).
     * Builds a regular grid by binning into approximate cell size.
     */
    fun loadCsvPoints(
        stream: InputStream,
        name: String = "dem.csv",
        targetCellDeg: Double = 0.0003,
    ): TerrainGrid {
        val points = ArrayList<Triple<Double, Double, Double>>()
        BufferedReader(InputStreamReader(stream)).use { br ->
            val header = br.readLine()?.lowercase(Locale.US) ?: error("Empty CSV")
            val cols = header.split(',', ';', '\t').map { it.trim() }
            fun idx(vararg names: String): Int =
                names.firstNotNullOfOrNull { n -> cols.indexOfFirst { it.contains(n) }.takeIf { it >= 0 } }
                    ?: -1
            val iLat = idx("lat", "y")
            val iLon = idx("lon", "lng", "long", "x")
            val iZ = idx("elev", "z", "height", "altitude", "dem")
            require(iLat >= 0 && iLon >= 0 && iZ >= 0) {
                "CSV needs lat, lon, elev columns; got $cols"
            }
            br.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val p = line.split(',', ';', '\t')
                val lat = p.getOrNull(iLat)?.toDoubleOrNull() ?: return@forEach
                val lon = p.getOrNull(iLon)?.toDoubleOrNull() ?: return@forEach
                val z = p.getOrNull(iZ)?.toDoubleOrNull() ?: return@forEach
                points.add(Triple(lat, lon, z))
            }
        }
        require(points.isNotEmpty()) { "No CSV elevation points" }

        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }
        val cell = targetCellDeg.coerceAtLeast(1e-6)
        val ncols = (((maxLon - minLon) / cell).toInt() + 1).coerceIn(2, 512)
        val nrows = (((maxLat - minLat) / cell).toInt() + 1).coerceIn(2, 512)
        val csLon = (maxLon - minLon) / (ncols - 1).coerceAtLeast(1)
        val csLat = (maxLat - minLat) / (nrows - 1).coerceAtLeast(1)

        val sum = DoubleArray(ncols * nrows)
        val count = IntArray(ncols * nrows)
        for ((lat, lon, z) in points) {
            val col = ((lon - minLon) / csLon).toInt().coerceIn(0, ncols - 1)
            val row = ((maxLat - lat) / csLat).toInt().coerceIn(0, nrows - 1)
            val i = row * ncols + col
            sum[i] += z
            count[i]++
        }
        val elev = FloatArray(ncols * nrows) { i ->
            if (count[i] > 0) (sum[i] / count[i]).toFloat() else Float.NaN
        }
        // fill holes with nearest
        fillNodata(elev, ncols, nrows, Float.NaN)

        return TerrainGrid(
            ncols = ncols,
            nrows = nrows,
            west = minLon,
            south = minLat,
            cellSizeLon = csLon,
            cellSizeLat = csLat,
            elevations = elev,
            nodata = -9999f,
            name = name,
        )
    }

    fun loadAuto(file: File): TerrainGrid {
        val n = file.name.lowercase(Locale.US)
        return when {
            n.endsWith(".asc") || n.endsWith(".grd") || n.endsWith(".txt") -> loadEsriAscii(file)
            n.endsWith(".csv") -> file.inputStream().use { loadCsvPoints(it, file.name) }
            else -> file.inputStream().use { loadEsriAscii(it, file.name) }
        }
    }

    /**
     * Synthetic DEM around [center] using [DemoTerrain] — offline terrain engine test.
     */
    fun generateDemoRegion(
        center: GeoPoint = GeoPoint(41.503, -74.01),
        halfSizeM: Double = 4000.0,
        cellSizeM: Double = 40.0,
        name: String = "demo-newburgh",
    ): TerrainGrid {
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(0.2)
        val halfLat = halfSizeM / mPerDegLat
        val halfLon = halfSizeM / mPerDegLon
        val csLat = cellSizeM / mPerDegLat
        val csLon = cellSizeM / mPerDegLon
        val nrows = ((2 * halfLat) / csLat).toInt().coerceIn(16, 256)
        val ncols = ((2 * halfLon) / csLon).toInt().coerceIn(16, 256)
        val south = center.lat - halfLat
        val west = center.lon - halfLon
        val elev = FloatArray(ncols * nrows)
        for (r in 0 until nrows) {
            val lat = south + (nrows - 1 - r) * csLat + csLat / 2
            for (c in 0 until ncols) {
                val lon = west + c * csLon + csLon / 2
                elev[r * ncols + c] = DemoTerrain.elevation(GeoPoint(lat, lon)).toFloat()
            }
        }
        return TerrainGrid(
            ncols = ncols,
            nrows = nrows,
            west = west,
            south = south,
            cellSizeLon = csLon,
            cellSizeLat = csLat,
            elevations = elev,
            name = name,
        )
    }

    /** Sample many points through bilinear terrain → sparse [ElevationGrid]. */
    fun toElevationGrid(terrain: TerrainGrid, points: List<GeoPoint>): ElevationGrid {
        val map = HashMap<String, Double>(points.size)
        for (p in points) {
            val z = terrain.sampleBilinear(p.lat, p.lon)
            if (z != null) map[p.key()] = z
        }
        return ElevationGrid(map, useDemo = false, terrain = terrain)
    }

    /**
     * Classic hillshade (0–255 gray) for terrain visualization.
     */
    fun hillshadeBitmap(
        terrain: TerrainGrid,
        azimuthDeg: Double = 315.0,
        altitudeDeg: Double = 45.0,
    ): Bitmap {
        val w = terrain.ncols
        val h = terrain.nrows
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val (cellX, cellY) = terrain.cellSizeMetersApprox()
        val az = Math.toRadians(360.0 - azimuthDeg + 90.0)
        val alt = Math.toRadians(altitudeDeg)
        val pixels = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                // Only use valid elevations — never feed NODATA (-9999) into Horn stencil.
                fun z(r: Int, c: Int): Double? = terrain.elevationAt(r, c)?.toDouble()
                val a = z(y - 1, x - 1)
                val b = z(y - 1, x)
                val c = z(y - 1, x + 1)
                val d = z(y, x - 1)
                val f = z(y, x + 1)
                val g = z(y + 1, x - 1)
                val hh = z(y + 1, x)
                val i = z(y + 1, x + 1)
                if (a == null || b == null || c == null || d == null ||
                    f == null || g == null || hh == null || i == null
                ) {
                    pixels[y * w + x] = Color.TRANSPARENT
                    continue
                }
                val dzdx = ((c + 2 * f + i) - (a + 2 * d + g)) / (8 * cellX)
                val dzdy = ((g + 2 * hh + i) - (a + 2 * b + c)) / (8 * cellY)
                val slope = atan(sqrt(dzdx * dzdx + dzdy * dzdy))
                val aspect =
                    when {
                        dzdx != 0.0 -> {
                            var asp = kotlin.math.atan2(dzdy, -dzdx)
                            if (asp < 0) asp += 2 * Math.PI
                            asp
                        }
                        else -> if (dzdy > 0) Math.PI / 2 else 1.5 * Math.PI
                    }
                val hs =
                    (cos(alt) * cos(slope) +
                        sin(alt) * sin(slope) * cos(az - aspect))
                        .coerceIn(0.0, 1.0)
                val g8 = (hs * 255).toInt()
                pixels[y * w + x] = Color.rgb(g8, g8, g8)
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun fillNodata(elev: FloatArray, ncols: Int, nrows: Int, nodata: Float) {
        // multi-pass nearest fill
        repeat(6) {
            val copy = elev.copyOf()
            for (r in 0 until nrows) {
                for (c in 0 until ncols) {
                    val i = r * ncols + c
                    if (!copy[i].isNaN() && copy[i] != nodata) continue
                    var sum = 0f
                    var n = 0
                    for (dr in -1..1) for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val rr = r + dr
                        val cc = c + dc
                        if (rr !in 0 until nrows || cc !in 0 until ncols) continue
                        val v = copy[rr * ncols + cc]
                        if (!v.isNaN() && v != nodata) {
                            sum += v
                            n++
                        }
                    }
                    if (n > 0) elev[i] = sum / n
                }
            }
        }
        for (i in elev.indices) {
            if (elev[i].isNaN()) elev[i] = 0f
        }
    }
}
