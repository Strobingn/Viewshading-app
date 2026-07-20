package com.viewshed.app.viewshed

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlin.math.cos

/**
 * Phase 1 — Offline elevation packs for field use.
 *
 * Google Map tiles cannot be redistributed offline; we cache **elevation samples**
 * (and pack metadata) so viewshed still works without network.
 */
class OfflineMapCache(context: Context) {

    private val root = File(context.filesDir, "offline_packs").also { it.mkdirs() }
    private val gson = Gson()

    data class Pack(
        val id: String,
        val name: String,
        val centerLat: Double,
        val centerLon: Double,
        val radiusKm: Double,
        val createdAt: Long,
        /** key "lat,lon" (US locale) → elevation meters */
        val elevations: Map<String, Double>
    ) {
        val center: GeoPoint get() = GeoPoint(centerLat, centerLon)
        val sampleCount: Int get() = elevations.size
    }

    fun listPacks(): List<Pack> =
        root.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { loadFile(it) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun getPack(id: String): Pack? {
        val f = File(root, "$id.json")
        return if (f.exists()) loadFile(f) else null
    }

    fun deletePack(id: String): Boolean {
        val f = File(root, "$id.json")
        return f.exists() && f.delete()
    }

    fun clearAll() {
        root.listFiles()?.forEach { it.delete() }
    }

    /**
     * Build a pack from an existing elevation map (e.g. after a calc or dedicated sample).
     */
    fun savePack(
        name: String,
        center: GeoPoint,
        radiusKm: Double,
        elevations: Map<String, Double>
    ): Pack {
        val pack = Pack(
            id = UUID.randomUUID().toString().take(8),
            name = name.ifBlank { "Pack ${listPacks().size + 1}" },
            centerLat = center.lat,
            centerLon = center.lon,
            radiusKm = radiusKm.coerceIn(0.2, 25.0),
            createdAt = System.currentTimeMillis(),
            elevations = elevations
        )
        File(root, "${pack.id}.json").writeText(gson.toJson(pack))
        return pack
    }

    /**
     * Sample a regular lat/lon grid around [center] and resolve elevations via [resolver].
     */
    suspend fun captureArea(
        name: String,
        center: GeoPoint,
        radiusKm: Double,
        gridSteps: Int = 24,
        resolver: suspend (List<GeoPoint>) -> Map<String, Double>
    ): Pack {
        val r = radiusKm.coerceIn(0.2, 25.0)
        val points = sampleGrid(center, r, gridSteps)
        val elev = resolver(points)
        return savePack(name, center, r, elev)
    }

    fun findCovering(point: GeoPoint): Pack? {
        return listPacks()
            .filter { pack ->
                GeoMath.distanceM(point, pack.center) <= pack.radiusKm * 1000.0 * 1.05
            }
            .maxByOrNull { it.sampleCount }
    }

    fun isOfflineAvailable(center: GeoPoint): Boolean = findCovering(center) != null

    /**
     * Lookup elevation from the best covering pack (exact key then nearest sample).
     */
    fun elevation(point: GeoPoint, maxNeighborM: Double = 120.0): Double? {
        val pack = findCovering(point) ?: return null
        pack.elevations[point.key()]?.let { return it }
        pack.elevations[point.key(5)]?.let { return it }

        var best = Double.MAX_VALUE
        var bestElev: Double? = null
        val latScale = 111_320.0
        val lonScale = 111_320.0 * cos(Math.toRadians(point.lat)).coerceAtLeast(0.2)
        for ((k, elev) in pack.elevations) {
            val parts = k.split(',')
            if (parts.size != 2) continue
            val lat = parts[0].toDoubleOrNull() ?: continue
            val lon = parts[1].toDoubleOrNull() ?: continue
            val dy = (lat - point.lat) * latScale
            val dx = (lon - point.lon) * lonScale
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < best && d <= maxNeighborM) {
                best = d
                bestElev = elev
            }
        }
        return bestElev
    }

    fun asGrid(center: GeoPoint): ElevationGrid? {
        val pack = findCovering(center) ?: return null
        return ElevationGrid(pack.elevations, useDemo = false)
    }

    fun mergeInto(
        base: Map<String, Double>,
        points: List<GeoPoint>
    ): Map<String, Double> {
        if (listPacks().isEmpty()) return base
        val out = base.toMutableMap()
        for (p in points) {
            if (out.containsKey(p.key())) continue
            elevation(p)?.let { out[p.key()] = it }
        }
        return out
    }

    private fun loadFile(file: File): Pack? = try {
        gson.fromJson(file.readText(), Pack::class.java)
    } catch (_: Exception) {
        null
    }

    companion object {
        fun sampleGrid(center: GeoPoint, radiusKm: Double, steps: Int): List<GeoPoint> {
            val n = steps.coerceIn(8, 48)
            val radiusM = radiusKm * 1000.0
            // Approximate degrees for bounding box
            val dLat = radiusM / 111_320.0
            val dLon = radiusM / (111_320.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(0.2))
            val minLat = center.lat - dLat
            val maxLat = center.lat + dLat
            val minLon = center.lon - dLon
            val maxLon = center.lon + dLon
            val pts = ArrayList<GeoPoint>((n + 1) * (n + 1))
            for (i in 0..n) {
                val lat = minLat + (maxLat - minLat) * i / n
                for (j in 0..n) {
                    val lon = minLon + (maxLon - minLon) * j / n
                    val p = GeoPoint(lat, lon)
                    if (GeoMath.distanceM(center, p) <= radiusM * 1.02) {
                        pts.add(p)
                    }
                }
            }
            if (pts.none { it.key() == center.key() }) pts.add(center)
            return pts
        }

        fun packSummary(packs: List<Pack>): String {
            if (packs.isEmpty()) return "No offline packs"
            val samples = packs.sumOf { it.sampleCount }
            return "${packs.size} pack(s), $samples samples"
        }
    }
}
