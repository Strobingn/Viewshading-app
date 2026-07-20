package com.viewshed.app.viewshed

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Phase 3 — Geotagged voice memos for field documentation.
 */
class VoiceMemoHelper(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "voice_memos").also { it.mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<VoiceMemo>>() {}.type

    private var recorder: MediaRecorder? = null
    private var activePath: String? = null
    private var activeLocation: GeoPoint? = null
    private var player: MediaPlayer? = null

    data class VoiceMemo(
        val id: String = UUID.randomUUID().toString(),
        val lat: Double,
        val lon: Double,
        val fileName: String,
        val createdAt: Long = System.currentTimeMillis(),
        val durationMs: Long = 0L
    ) {
        val location: GeoPoint get() = GeoPoint(lat, lon)
        fun file(root: File): File = File(root, fileName)
    }

    val isRecording: Boolean get() = recorder != null

    fun list(): List<VoiceMemo> = load().sortedByDescending { it.createdAt }

    fun absoluteFile(memo: VoiceMemo): File = memo.file(dir)

    /**
     * Start recording at [location]. Requires RECORD_AUDIO permission.
     * @return absolute path of the new file, or null on failure.
     */
    fun startRecording(location: GeoPoint): String? {
        if (recorder != null) stopRecording()
        val id = UUID.randomUUID().toString().take(12)
        val fileName = "memo_$id.m4a"
        val out = File(dir, fileName)
        return try {
            val rec = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            activePath = out.absolutePath
            activeLocation = location
            out.absolutePath
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            activePath = null
            activeLocation = null
            out.delete()
            null
        }
    }

    /**
     * Stop recording and index the memo.
     * @return saved [VoiceMemo] or null if nothing was recording / failed.
     */
    fun stopRecording(): VoiceMemo? {
        val rec = recorder ?: return null
        val path = activePath
        val loc = activeLocation
        return try {
            rec.stop()
            rec.release()
            recorder = null
            if (path == null || loc == null) return null
            val file = File(path)
            if (!file.exists() || file.length() < 32) {
                file.delete()
                return null
            }
            val memo = VoiceMemo(
                lat = loc.lat,
                lon = loc.lon,
                fileName = file.name,
                durationMs = estimateDurationMs(file)
            )
            val all = load()
            all.add(memo)
            save(all)
            activePath = null
            activeLocation = null
            memo
        } catch (e: Exception) {
            try {
                rec.reset()
            } catch (_: Exception) {
            }
            rec.release()
            recorder = null
            activePath = null
            activeLocation = null
            null
        }
    }

    fun play(memo: VoiceMemo, onComplete: (() -> Unit)? = null): Boolean {
        stopPlayback()
        val file = absoluteFile(memo)
        if (!file.exists()) return false
        return try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stopPlayback()
                    onComplete?.invoke()
                }
                prepare()
                start()
            }
            true
        } catch (_: Exception) {
            stopPlayback()
            false
        }
    }

    fun stopPlayback() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
    }

    fun delete(id: String): Boolean {
        val all = load()
        val memo = all.find { it.id == id } ?: return false
        absoluteFile(memo).delete()
        all.removeAll { it.id == id }
        save(all)
        return true
    }

    fun release() {
        if (isRecording) stopRecording()
        stopPlayback()
    }

    private fun estimateDurationMs(file: File): Long {
        // Cheap estimate; real duration available after MediaPlayer prepare if needed
        return maxOf(0L, file.length() / 16) // ~128kbps AAC rough
    }

    private fun load(): MutableList<VoiceMemo> {
        if (!indexFile.exists()) return mutableListOf()
        return try {
            gson.fromJson<MutableList<VoiceMemo>>(indexFile.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(list: List<VoiceMemo>) {
        indexFile.writeText(gson.toJson(list))
    }
}
