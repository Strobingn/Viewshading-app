package com.viewshed.app.viewshed

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.Instant
import java.util.UUID

/** A durable field waypoint with GPS quality and optional observation metadata. */
data class FieldWaypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val point: GeoPoint,
    val altitudeM: Double? = null,
    val horizontalAccuracyM: Float? = null,
    val verticalAccuracyM: Float? = null,
    val eyeHeightM: Double = 1.7,
    val notes: String = "",
    val capturedAtEpochMs: Long = System.currentTimeMillis()
)

data class FieldTrackPoint(
    val point: GeoPoint,
    val altitudeM: Double? = null,
    val accuracyM: Float? = null,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

data class FieldTrack(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val points: List<FieldTrackPoint>,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val distanceM: Double
        get() = points.zipWithNext().sumOf { (a, b) -> GeoMath.distanceM(a.point, b.point) }
}

data class FieldProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val waypoints: List<FieldWaypoint> = emptyList(),
    val tracks: List<FieldTrack> = emptyList(),
    val sessions: List<AnalysisSession> = emptyList(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

/** Offline-first JSON project store using atomic replacement. */
class FieldProjectStore(context: Context) {
    private val gson = Gson()
    private val directory = File(context.filesDir, "field_projects").apply { mkdirs() }

    fun save(project: FieldProject) {
        val target = file(project.id)
        val temporary = File(directory, "${project.id}.tmp")
        temporary.writeText(gson.toJson(project.copy(updatedAtEpochMs = System.currentTimeMillis())))
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IllegalStateException("Unable to replace field project ${project.id}")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("Unable to commit field project ${project.id}")
        }
    }

    fun load(id: String): FieldProject? = file(id).takeIf(File::exists)?.reader()?.use {
        gson.fromJson(it, FieldProject::class.java)
    }

    fun list(): List<FieldProject> = directory.listFiles { f -> f.extension == "json" }
        ?.mapNotNull { runCatching { it.reader().use { reader -> gson.fromJson(reader, FieldProject::class.java) } }.getOrNull() }
        ?.sortedByDescending { it.updatedAtEpochMs }
        .orEmpty()

    fun delete(id: String): Boolean = file(id).delete()

    private fun file(id: String) = File(directory, "$id.json")
}

/** Validates whether a location fix is suitable for a field viewshed observation. */
object GpsQualityGate {
    data class Assessment(val accepted: Boolean, val grade: Grade, val message: String)
    enum class Grade { EXCELLENT, GOOD, MARGINAL, REJECTED }

    fun assess(horizontalAccuracyM: Float?, ageMs: Long, maxAccuracyM: Float = 25f): Assessment {
        if (horizontalAccuracyM == null) return Assessment(false, Grade.REJECTED, "GPS accuracy unavailable")
        if (ageMs > 120_000L) return Assessment(false, Grade.REJECTED, "GPS fix is older than 2 minutes")
        return when {
            horizontalAccuracyM <= 5f -> Assessment(true, Grade.EXCELLENT, "Survey-quality mobile fix")
            horizontalAccuracyM <= 12f -> Assessment(true, Grade.GOOD, "Good field fix")
            horizontalAccuracyM <= maxAccuracyM -> Assessment(true, Grade.MARGINAL, "Usable with reduced confidence")
            else -> Assessment(false, Grade.REJECTED, "Accuracy ${horizontalAccuracyM.toInt()} m exceeds limit")
        }
    }
}

/** GPX 1.1 exporter for observer waypoints and recorded tracks. */
object FieldGpxExport {
    fun export(project: FieldProject): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Viewshade\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        project.waypoints.forEach { waypoint ->
            append("  <wpt lat=\"${waypoint.point.lat}\" lon=\"${waypoint.point.lon}\">\n")
            waypoint.altitudeM?.let { append("    <ele>$it</ele>\n") }
            append("    <time>${Instant.ofEpochMilli(waypoint.capturedAtEpochMs)}</time>\n")
            append("    <name>${escape(waypoint.name)}</name>\n")
            if (waypoint.notes.isNotBlank()) append("    <desc>${escape(waypoint.notes)}</desc>\n")
            append("  </wpt>\n")
        }
        project.tracks.forEach { track ->
            append("  <trk><name>${escape(track.name)}</name><trkseg>\n")
            track.points.forEach { point ->
                append("    <trkpt lat=\"${point.point.lat}\" lon=\"${point.point.lon}\">")
                point.altitudeM?.let { append("<ele>$it</ele>") }
                append("<time>${Instant.ofEpochMilli(point.timestampEpochMs)}</time></trkpt>\n")
            }
            append("  </trkseg></trk>\n")
        }
        append("</gpx>")
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

/** CSV export for field logs and elevation profiles. */
object FieldCsvExport {
    fun waypoints(project: FieldProject): String = buildString {
        appendLine("id,name,latitude,longitude,altitude_m,accuracy_m,eye_height_m,captured_at,notes")
        project.waypoints.forEach { w ->
            append(csv(w.id)).append(',').append(csv(w.name)).append(',')
            append(w.point.lat).append(',').append(w.point.lon).append(',')
            append(w.altitudeM ?: "").append(',').append(w.horizontalAccuracyM ?: "").append(',')
            append(w.eyeHeightM).append(',').append(Instant.ofEpochMilli(w.capturedAtEpochMs)).append(',')
            appendLine(csv(w.notes))
        }
    }

    fun elevationProfiles(result: ViewshedResult): String = buildString {
        appendLine("bearing_deg,distance_m,latitude,longitude,elevation_m,visible")
        result.elevationProfiles.forEach { profile ->
            profile.samples.forEach { sample ->
                append(profile.bearingDeg).append(',').append(sample.distanceM).append(',')
                append(sample.point.lat).append(',').append(sample.point.lon).append(',')
                append(sample.terrainElevationM).append(',').appendLine(sample.visible)
            }
        }
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}

/** Lightweight field package manifest for offline transfer and auditability. */
data class OfflineFieldPackage(
    val project: FieldProject,
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
    val appFormatVersion: Int = 1,
    val includedFiles: List<String> = emptyList()
)
