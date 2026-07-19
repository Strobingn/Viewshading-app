package com.viewshed.app.viewshed

import java.io.File

/**
 * Local DEM Loader - supports simple ASCII Grid format now.
 * GeoTIFF support can be added via GDAL JNI or pure Kotlin parser later.
 */
object LocalDemLoader {

    fun loadAsciiGrid(path: String): ElevationRepository? {
        val file = File(path)
        if (!file.exists()) return null

        val lines = file.readLines()
        // Very simple ASCII Grid parser (ncols, nrows, xllcorner, yllcorner, cellsize, NODATA)
        var ncols = 0
        var nrows = 0
        var xll = 0.0
        var yll = 0.0
        var cellsize = 1.0
        var nodata = -9999.0
        val dataStart = lines.indexOfFirst { it.trim().startsWith("NODATA") || it.contains(".") && it.split("\\s+".toRegex()).size > 5 } + 1

        lines.forEach { line ->
            when {
                line.startsWith("ncols") -> ncols = line.split("\\s+".toRegex())[1].toInt()
                line.startsWith("nrows") -> nrows = line.split("\\s+".toRegex())[1].toInt()
                line.startsWith("xllcorner") -> xll = line.split("\\s+".toRegex())[1].toDouble()
                line.startsWith("yllcorner") -> yll = line.split("\\s+".toRegex())[1].toDouble()
                line.startsWith("cellsize") -> cellsize = line.split("\\s+".toRegex())[1].toDouble()
                line.startsWith("NODATA_value") -> nodata = line.split("\\s+".toRegex())[1].toDouble()
            }
        }

        // Build simple in-memory grid (production: use tiled or memory-mapped)
        val grid = mutableListOf<DoubleArray>()
        for (i in dataStart until lines.size) {
            val row = lines[i].trim().split("\\s+".toRegex()).map { it.toDoubleOrNull() ?: nodata }.toDoubleArray()
            if (row.size == ncols) grid.add(row)
        }

        return object : ElevationRepository {
            override fun getElevation(point: GeoPoint): Double {
                // Simple nearest neighbor lookup
                val col = ((point.longitude - xll) / cellsize).toInt().coerceIn(0, ncols - 1)
                val row = ((point.latitude - yll) / cellsize).toInt().coerceIn(0, nrows - 1)
                return grid.getOrNull(row)?.getOrNull(col) ?: nodata
            }
        }
    }

    fun loadFromFile(path: String): ElevationRepository? {
        return when {
            path.endsWith(".asc") || path.endsWith(".grd") -> loadAsciiGrid(path)
            else -> null // TODO: GeoTIFF support
        }
    }
}
