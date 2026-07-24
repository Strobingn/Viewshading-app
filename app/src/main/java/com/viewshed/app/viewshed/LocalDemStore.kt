package com.viewshed.app.viewshed

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Durable, app-private DEM library for offline reopening and deterministic cleanup. */
class LocalDemStore(private val context: Context) {
    data class Entry(
        val id: String,
        val name: String,
        val fileName: String,
        val byteCount: Long,
        val importedAt: Long,
        val source: String,
    )

    private val directory = File(context.filesDir, "local_dems").apply { mkdirs() }
    private val indexFile = File(directory, "index.json")
    private val gson = Gson()
    private val listType = object : TypeToken<List<Entry>>() {}.type

    fun list(): List<Entry> = synchronized(LOCK) {
        if (!indexFile.exists()) return@synchronized emptyList()
        runCatching { gson.fromJson<List<Entry>>(indexFile.readText(), listType).orEmpty() }
            .getOrDefault(emptyList())
            .filter { file(it).exists() }
            .sortedByDescending(Entry::importedAt)
    }

    fun file(entry: Entry): File = File(directory, File(entry.fileName).name)

    suspend fun importFromUri(uri: Uri, displayName: String): Entry = withContext(Dispatchers.IO) {
        val extension = supportedExtension(displayName)
        val id = UUID.randomUUID().toString()
        val target = File(directory, "$id.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copyLimited(input, target)
            } ?: throw ElevationDataException("Unable to open selected DEM")
            saveEntry(displayName, target, uri.toString())
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    suspend fun download(url: String, displayName: String): Entry = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "DEM URL must use http or https" }
        val extension = supportedExtension(displayName.ifBlank { url.substringBefore('?').substringAfterLast('/') })
        val id = UUID.randomUUID().toString()
        val target = File(directory, "$id.$extension")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Viewshade-Android/1.7")
        try {
            if (connection.responseCode !in 200..299) {
                throw ElevationDataException("DEM server returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.buffered().use { input -> copyLimited(input, target) }
            saveEntry(displayName.ifBlank { "Downloaded DEM.$extension" }, target, url)
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun delete(entry: Entry): Boolean = synchronized(LOCK) {
        val deleted = file(entry).delete()
        writeIndex(list().filterNot { it.id == entry.id })
        deleted
    }

    private fun saveEntry(name: String, target: File, source: String): Entry = synchronized(LOCK) {
        val entry = Entry(
            id = target.nameWithoutExtension,
            name = name.ifBlank { target.name },
            fileName = target.name,
            byteCount = target.length(),
            importedAt = System.currentTimeMillis(),
            source = source,
        )
        writeIndex(list().filterNot { it.id == entry.id } + entry)
        entry
    }

    private fun writeIndex(entries: List<Entry>) {
        val temporary = File(directory, "index.json.tmp")
        temporary.writeText(gson.toJson(entries))
        if (!temporary.renameTo(indexFile)) {
            indexFile.writeText(gson.toJson(entries))
            temporary.delete()
        }
    }

    private fun copyLimited(input: java.io.InputStream, target: File) {
        val temporary = File(target.parentFile, "${target.name}.partial")
        var total = 0L
        temporary.outputStream().buffered().use { output ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_DEM_BYTES) throw ElevationDataException("DEM exceeds the 2 GiB mobile limit")
                output.write(buffer, 0, count)
            }
        }
        if (total == 0L) throw ElevationDataException("DEM download was empty")
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun supportedExtension(name: String): String {
        val extension = name.substringBefore('?').substringAfterLast('.', "").lowercase()
        require(extension in SUPPORTED) { "Use a GeoTIFF, ASCII Grid, or elevation CSV" }
        return extension
    }

    companion object {
        private val LOCK = Any()
        private const val MAX_DEM_BYTES = 2L * 1024 * 1024 * 1024
        private val SUPPORTED = setOf("tif", "tiff", "asc", "ascii", "grd", "csv", "txt")
    }
}
