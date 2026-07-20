package com.viewshed.app.viewshed

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Phase 5 — Geotag photos with observer / GPS location via Exif.
 */
class PhotoGeotagHelper(private val context: Context) {

    data class GeotaggedPhoto(
        val id: String,
        val path: String,
        val lat: Double,
        val lon: Double,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val dir = File(context.filesDir, "geotagged_photos").also { it.mkdirs() }
    private val index = File(dir, "index.json")

    fun attachLocationToFile(photoFile: File, location: GeoPoint): Boolean {
        return try {
            val exif = ExifInterface(photoFile.absolutePath)
            exif.setLatLong(location.lat, location.lon)
            exif.saveAttributes()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun importAndGeotag(uri: Uri, location: GeoPoint): GeotaggedPhoto? {
        return try {
            val id = UUID.randomUUID().toString().take(12)
            val out = File(dir, "photo_$id.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: return null
            attachLocationToFile(out, location)
            val photo = GeotaggedPhoto(id, out.absolutePath, location.lat, location.lon)
            appendIndex(photo)
            photo
        } catch (_: Exception) {
            null
        }
    }

    fun list(): List<GeotaggedPhoto> {
        if (!index.exists()) return emptyList()
        return try {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<GeotaggedPhoto>>() {}.type
            gson.fromJson(index.readText(), type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun appendIndex(photo: GeotaggedPhoto) {
        val gson = com.google.gson.Gson()
        val all = list().toMutableList()
        all.add(0, photo)
        index.writeText(gson.toJson(all.take(100)))
    }
}
