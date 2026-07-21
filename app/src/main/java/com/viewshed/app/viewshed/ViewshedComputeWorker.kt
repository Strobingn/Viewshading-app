package com.viewshed.app.viewshed

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class BackgroundViewshedInput(
    val observer: GeoPoint,
    val params: ViewshedParams,
    val elevations: Map<String, Double>,
)

/** Durable fixed-grid LOS calculation. Elevations are resolved before enqueueing. */
class ViewshedComputeWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val gson = Gson()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val inputName = inputData.getString(KEY_INPUT) ?: return@withContext Result.failure()
        val directory = directory(applicationContext)
        val input = File(directory, File(inputName).name)
        if (!input.exists()) return@withContext Result.failure(workDataOf(KEY_ERROR to "Input expired"))
        try {
            val job = gson.fromJson(input.readText(), BackgroundViewshedInput::class.java)
            val result = ViewshedEngine.compute(
                observer = job.observer,
                params = job.params,
                elevations = ElevationGrid(job.elevations, useDemo = false),
                onRayProgress = { done, total ->
                    setProgress(workDataOf(KEY_PROGRESS to if (total == 0) 0 else done * 100 / total))
                },
            )
            val output = File(directory, "result-${id}.json")
            writeAtomically(output, gson.toJson(result))
            AnalysisSessionManager.save(
                AnalysisSession.fromResult(result),
                File(applicationContext.filesDir, "last_session.json"),
            )
            SessionHistory(applicationContext).add(result, "Background viewshed")
            Result.success(workDataOf(KEY_OUTPUT to output.name))
        } catch (error: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: error.javaClass.simpleName)))
        } finally {
            input.delete()
        }
    }

    companion object {
        const val TAG = "viewshed-background"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT = "output"
        const val KEY_ERROR = "error"
        private const val KEY_INPUT = "input"

        suspend fun prepare(
            context: Context,
            observer: GeoPoint,
            params: ViewshedParams,
            points: List<GeoPoint>,
            elevations: ElevationGrid,
        ): OneTimeWorkRequest = withContext(Dispatchers.IO) {
            val name = "input-${UUID.randomUUID()}.json"
            val file = File(directory(context), name)
            val values = LinkedHashMap<String, Double>(points.size)
            points.forEach { point -> values[point.key()] = elevations.elevation(point) }
            writeAtomically(file, Gson().toJson(BackgroundViewshedInput(observer, params, values)))
            OneTimeWorkRequestBuilder<ViewshedComputeWorker>()
                .setInputData(workDataOf(KEY_INPUT to name))
                .addTag(TAG)
                .build()
        }

        fun readResult(context: Context, data: Data): ViewshedResult? {
            val name = data.getString(KEY_OUTPUT) ?: return null
            val file = File(directory(context), File(name).name)
            return try {
                Gson().fromJson(file.readText(), ViewshedResult::class.java).also { file.delete() }
            } catch (_: Exception) {
                null
            }
        }

        private fun directory(context: Context): File =
            File(context.filesDir, "background_viewsheds").apply { mkdirs() }

        private fun writeAtomically(target: File, value: String) {
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(value)
            if (!temporary.renameTo(target)) {
                target.writeText(value)
                temporary.delete()
            }
        }
    }
}
