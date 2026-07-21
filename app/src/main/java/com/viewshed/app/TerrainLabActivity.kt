package com.viewshed.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.viewshed.app.databinding.ActivityTerrainLabBinding
import com.viewshed.app.ui.TerrainInteractionMode
import com.viewshed.app.viewshed.LocalDemLoader
import com.viewshed.app.viewshed.RasterDemSource
import com.viewshed.app.viewshed.terrain.GroundSurfaceMode
import com.viewshed.app.viewshed.terrain.LidarImportOptions
import com.viewshed.app.viewshed.terrain.LidarTerrainLoader
import com.viewshed.app.viewshed.terrain.TerrainAnalysis
import com.viewshed.app.viewshed.terrain.TerrainEngine
import com.viewshed.app.viewshed.terrain.TerrainFeatureCandidate
import com.viewshed.app.viewshed.terrain.TerrainRaster
import com.viewshed.app.viewshed.terrain.TerrainRenderMode
import com.viewshed.app.viewshed.terrain.TerrainRenderOptions
import com.viewshed.app.viewshed.terrain.TerrainWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class TerrainLabActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTerrainLabBinding
    private var raster: TerrainRaster? = null
    private var renderMode = TerrainRenderMode.HILLSHADE
    private var contoursVisible = false
    private var candidates: List<TerrainFeatureCandidate> = emptyList()
    private var renderJob: Job? = null

    private val pickTerrain = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadTerrain(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applySaved(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityTerrainLabBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.terrainLabRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setupUi()
        val initial = TerrainWorkspace.current ?: TerrainRaster.from(TerrainEngine.generateDemoRegion())
        setRaster(initial)
    }

    private fun setupUi() {
        binding.terrainToolbar.setNavigationOnClickListener { finish() }
        val labels = TerrainRenderMode.entries.map { it.label }
        binding.dropdownTerrainMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        binding.dropdownTerrainMode.setText(renderMode.label, false)
        binding.dropdownTerrainMode.setOnItemClickListener { _, _, position, _ ->
            renderMode = TerrainRenderMode.entries[position]
            scheduleRender()
        }
        binding.btnTerrainImport.setOnClickListener {
            pickTerrain.launch(
                arrayOf(
                    "image/tiff", "application/octet-stream", "application/x-las",
                    "application/x-laz", "text/plain", "text/csv", "*/*",
                ),
            )
        }
        binding.btnTerrain2d.setOnClickListener {
            binding.terrainCanvas.setThreeDimensional(false)
            binding.terrainCanvas.interactionMode = TerrainInteractionMode.PAN
            toast("2D terrain: pinch to zoom, drag to pan, double-tap to fit")
        }
        binding.btnTerrain3d.setOnClickListener {
            binding.terrainCanvas.setThreeDimensional(true)
            binding.terrainCanvas.interactionMode = TerrainInteractionMode.PAN
            toast("3D mesh: drag to orbit, pinch for vertical scale, double-tap to reset")
        }
        binding.btnTerrainProfile.setOnClickListener {
            binding.terrainCanvas.setThreeDimensional(false)
            binding.terrainCanvas.interactionMode = TerrainInteractionMode.PROFILE
            toast("Tap the profile start and end points")
        }
        binding.btnTerrainMeasure.setOnClickListener {
            binding.terrainCanvas.setThreeDimensional(false)
            binding.terrainCanvas.interactionMode = TerrainInteractionMode.MEASURE
            toast("Tap two terrain points")
        }
        binding.terrainCanvas.onMeasurement = { distance, relief ->
            binding.tvTerrainLabStatus.text = String.format(
                Locale.US,
                "Measurement: %,.1f m horizontal · %+.1f m elevation change",
                distance,
                relief,
            )
        }
        binding.terrainCanvas.onProfile = { points ->
            val min = points.minOfOrNull { it.elevationM } ?: 0.0
            val max = points.maxOfOrNull { it.elevationM } ?: 0.0
            val distance = points.lastOrNull()?.distanceM ?: 0.0
            binding.tvTerrainLabStatus.text = String.format(
                Locale.US,
                "Profile: %,.1f m · elevation %.1f–%.1f m · %d samples",
                distance, min, max, points.size,
            )
        }
        binding.btnTerrainContours.setOnClickListener { toggleContours() }
        binding.btnTerrainDetect.setOnClickListener { detectFeatures() }
        binding.btnTerrainExport.setOnClickListener { showExportMenu() }
        binding.btnTerrainToggleControls.setOnClickListener {
            val hide = binding.terrainControls.visibility == View.VISIBLE
            binding.terrainControls.visibility = if (hide) View.GONE else View.VISIBLE
            binding.btnTerrainToggleControls.text = if (hide) "Show controls" else "Hide controls"
        }
        binding.sliderTerrainAzimuth.addOnChangeListener { _, _, fromUser -> if (fromUser) scheduleRender() }
        binding.sliderTerrainAltitude.addOnChangeListener { _, _, fromUser -> if (fromUser) scheduleRender() }
        binding.sliderTerrainExaggeration.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.terrainCanvas.setVerticalExaggeration(value)
                scheduleRender()
            }
        }
    }

    private fun loadTerrain(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "terrain"
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
        binding.tvTerrainLabStatus.text = "Loading $name…"
        binding.btnTerrainImport.isEnabled = false
        lifecycleScope.launch {
            try {
                val loaded = when (extension) {
                    "las", "laz" -> LidarTerrainLoader.load(
                        this@TerrainLabActivity,
                        uri,
                        LidarImportOptions(groundMode = selectedGroundMode()),
                    )
                    "tif", "tiff", "asc", "ascii", "grd" -> {
                        val dem = LocalDemLoader.load(this@TerrainLabActivity, uri)
                        val rasterSource = dem as? RasterDemSource
                            ?: error("This DEM cannot be rendered as a raster")
                        withContext(Dispatchers.Default) { TerrainRaster.from(rasterSource, name) }
                    }
                    "csv", "txt" -> withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            TerrainRaster.from(TerrainEngine.loadCsvPoints(input, name))
                        } ?: error("Unable to open terrain file")
                    }
                    else -> error("Unsupported file. Choose GeoTIFF, ASC, CSV, LAS, or LAZ.")
                }
                setRaster(loaded)
            } catch (error: Exception) {
                binding.tvTerrainLabStatus.text = "Import failed: ${error.message ?: error.javaClass.simpleName}"
                toast(binding.tvTerrainLabStatus.text.toString())
            } finally {
                binding.btnTerrainImport.isEnabled = true
            }
        }
    }

    private fun selectedGroundMode(): GroundSurfaceMode = when {
        binding.chipGroundAutomatic.isChecked -> GroundSurfaceMode.AUTO_LOWEST
        binding.chipGroundSurface.isChecked -> GroundSurfaceMode.SURFACE_MODEL
        else -> GroundSurfaceMode.SOURCE_CLASSIFIED
    }

    private fun setRaster(value: TerrainRaster) {
        raster = value
        TerrainWorkspace.current = value
        contoursVisible = false
        candidates = emptyList()
        binding.terrainCanvas.setContours(emptyList())
        binding.terrainCanvas.setCandidates(emptyList())
        scheduleRender(immediate = true)
        val warning = value.metadata.warning?.let { " · $it" }.orEmpty()
        binding.tvTerrainLabStatus.text = String.format(
            Locale.US,
            "%s · %d×%d · %.1f–%.1f m · %.2f×%.2f m cells%s",
            value.name,
            value.width,
            value.height,
            value.minElevationM,
            value.maxElevationM,
            value.cellWidthM,
            value.cellHeightM,
            warning,
        )
    }

    private fun scheduleRender(immediate: Boolean = false) {
        val terrain = raster ?: return
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            if (!immediate) delay(90)
            val options = currentRenderOptions()
            binding.tvTerrainLighting.text = String.format(
                Locale.US,
                "Light direction %.0f° · altitude %.0f° · vertical %.1f×",
                options.lightAzimuthDeg,
                options.lightAltitudeDeg,
                options.verticalExaggeration,
            )
            val bitmap = withContext(Dispatchers.Default) { TerrainAnalysis.render(terrain, options) }
            if (binding.terrainCanvas.width > 0 && raster === terrain) {
                if (immediate) binding.terrainCanvas.setTerrain(terrain, bitmap)
                else binding.terrainCanvas.updateImage(bitmap)
            } else if (raster === terrain) {
                binding.terrainCanvas.setTerrain(terrain, bitmap)
            }
        }
    }

    private fun currentRenderOptions() = TerrainRenderOptions(
        mode = renderMode,
        lightAzimuthDeg = binding.sliderTerrainAzimuth.value.toDouble(),
        lightAltitudeDeg = binding.sliderTerrainAltitude.value.toDouble(),
        verticalExaggeration = binding.sliderTerrainExaggeration.value.toDouble(),
        neighborhoodRadius = 8,
    )

    private fun toggleContours() {
        val terrain = raster ?: return
        if (contoursVisible) {
            contoursVisible = false
            binding.terrainCanvas.setContours(emptyList())
            binding.btnTerrainContours.text = "Contours"
            return
        }
        val interval = chooseContourInterval(terrain)
        lifecycleScope.launch {
            binding.tvTerrainLabStatus.text = "Extracting ${interval.toInt()} m contours…"
            val lines = withContext(Dispatchers.Default) { TerrainAnalysis.contours(terrain, interval) }
            contoursVisible = true
            binding.terrainCanvas.setContours(lines)
            binding.btnTerrainContours.text = "Hide contours"
            binding.tvTerrainLabStatus.text = "${lines.size} contour segments at ${interval.toInt()} m interval"
        }
    }

    private fun chooseContourInterval(terrain: TerrainRaster): Double {
        val range = (terrain.maxElevationM - terrain.minElevationM).toDouble()
        return when {
            range < 20 -> 1.0
            range < 100 -> 5.0
            range < 400 -> 10.0
            else -> 20.0
        }
    }

    private fun detectFeatures() {
        val terrain = raster ?: return
        binding.btnTerrainDetect.isEnabled = false
        binding.tvTerrainLabStatus.text = "Scanning local relief for foundations and disturbed ground…"
        lifecycleScope.launch {
            try {
                candidates = withContext(Dispatchers.Default) {
                    TerrainAnalysis.detectFeatures(terrain, thresholdM = 0.45, radius = 8)
                }
                binding.terrainCanvas.setCandidates(candidates)
                if (candidates.isEmpty()) {
                    binding.tvTerrainLabStatus.text = "No strong anomalies found at the 0.45 m threshold."
                } else {
                    val foundations = candidates.count { "foundation" in it.type.lowercase(Locale.US) }
                    binding.tvTerrainLabStatus.text =
                        "${candidates.size} candidates · $foundations possible foundations · yellow boxes are leads, not proof"
                    showCandidateSummary()
                }
            } finally {
                binding.btnTerrainDetect.isEnabled = true
            }
        }
    }

    private fun showCandidateSummary() {
        val labels = candidates.take(30).mapIndexed { index, item ->
            String.format(
                Locale.US,
                "%d. %s · %.0f m² · %.1f m relief · %.0f%%",
                index + 1,
                item.type,
                item.areaM2,
                item.reliefM,
                item.confidence * 100,
            )
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Terrain candidates")
            .setMessage("Algorithmic leads require field verification. Features can be natural, modern, or processing artifacts.")
            .setItems(labels, null)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showExportMenu() {
        val items = arrayOf("Viewport PNG", "Feature GeoJSON", "Feature CSV")
        MaterialAlertDialogBuilder(this)
            .setTitle("Export terrain analysis")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exportSnapshot()
                    1 -> shareText(featureGeoJson(), "application/geo+json", "terrain-features.geojson")
                    2 -> shareText(featureCsv(), "text/csv", "terrain-features.csv")
                }
            }
            .show()
    }

    private fun exportSnapshot() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = withContext(Dispatchers.Main) { binding.terrainCanvas.snapshot() }
                val directory = File(cacheDir, "shared").apply { mkdirs() }
                val file = File(directory, "terrain-analysis-${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = FileProvider.getUriForFile(this@TerrainLabActivity, "$packageName.files", file)
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share terrain image"))
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { toast("Image export failed: ${error.message}") }
            }
        }
    }

    private fun featureGeoJson(): String {
        val terrain = raster ?: return "{\"type\":\"FeatureCollection\",\"features\":[]}"
        val features = candidates.joinToString(",") { item ->
            val point = terrain.indexToGeo(item.centerRow, item.centerColumn)
            val geometry = if (point == null) "null" else
                "{\"type\":\"Point\",\"coordinates\":[${point.lon},${point.lat}]}"
            "{\"type\":\"Feature\",\"geometry\":$geometry,\"properties\":{" +
                "\"type\":${json(item.type)},\"area_m2\":${item.areaM2}," +
                "\"relief_m\":${item.reliefM},\"confidence\":${item.confidence}," +
                "\"raster_row\":${item.centerRow},\"raster_column\":${item.centerColumn}}}"
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
    }

    private fun featureCsv(): String = buildString {
        appendLine("type,latitude,longitude,raster_row,raster_column,area_m2,relief_m,confidence")
        val terrain = raster
        candidates.forEach { item ->
            val point = terrain?.indexToGeo(item.centerRow, item.centerColumn)
            append(json(item.type)).append(',').append(point?.lat ?: "").append(',')
                .append(point?.lon ?: "").append(',').append(item.centerRow).append(',')
                .append(item.centerColumn).append(',').append(item.areaM2).append(',')
                .append(item.reliefM).append(',').appendLine(item.confidence)
        }
    }

    private fun shareText(body: String, mime: String, name: String) {
        if (candidates.isEmpty()) {
            toast("Run Find features before exporting candidates.")
            return
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, body)
        }, "Share $name"))
    }

    private fun json(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
