package com.viewshed.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.viewshed.app.databinding.ActivityMainBinding
import com.viewshed.app.data.RemoteLayerType
import com.viewshed.app.data.RemoteMapLayerSpec
import com.viewshed.app.data.RemoteMapLayerStore
import com.viewshed.app.data.RemoteMapLayers
import com.viewshed.app.performance.PerformanceMonitor
import com.viewshed.app.performance.AdaptiveComputeController
import com.viewshed.app.performance.ComputeHealth
import com.viewshed.app.viewshed.AnalysisPreset
import com.viewshed.app.viewshed.AnalysisSession
import com.viewshed.app.viewshed.AnalysisSessionManager
import com.viewshed.app.viewshed.AdvancedExport
import com.viewshed.app.viewshed.CompassHelper
import com.viewshed.app.viewshed.ElevationRepository
import com.viewshed.app.viewshed.ElevationGrid
import com.viewshed.app.viewshed.FavoritesManager
import com.viewshed.app.viewshed.FieldDataForms
import com.viewshed.app.viewshed.FieldNotesManager
import com.viewshed.app.viewshed.GeoExport
import com.viewshed.app.viewshed.GeoPoint
import com.viewshed.app.viewshed.MeasurementTool
import com.viewshed.app.viewshed.LocalDemLoader
import com.viewshed.app.viewshed.LocalDemStore
import com.viewshed.app.viewshed.MappedFloatDem
import com.viewshed.app.viewshed.RasterDemSource
import com.viewshed.app.viewshed.OfflineMapCache
import com.viewshed.app.viewshed.PhotoGeotagHelper
import com.viewshed.app.viewshed.ProfessionalAnalysis
import com.viewshed.app.viewshed.QrExport
import com.viewshed.app.viewshed.SampleQuality
import com.viewshed.app.viewshed.SessionHistory
import com.viewshed.app.viewshed.BackendViewshedClient
import com.viewshed.app.viewshed.ViewshedEngine
import com.viewshed.app.viewshed.ViewshedParams
import com.viewshed.app.viewshed.ViewshedResult
import com.viewshed.app.viewshed.VoiceMemoHelper
import com.viewshed.app.viewshed.VulkanViewshed
import com.viewshed.app.viewshed.ViewshedComputeWorker
import com.viewshed.app.viewshed.terrain.TerrainEngine
import com.viewshed.app.viewshed.terrain.TerrainRaster
import com.viewshed.app.viewshed.terrain.TerrainWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Viewshed Calculator — Google Maps + radial LOS (horizon method).
 *
 * Long-press map → place observer → Calculate.
 * Demo terrain offline; real mode uses Google Elevation API.
 */
class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<*>

    private val elevationRepo = ElevationRepository()
    private lateinit var offlineCache: OfflineMapCache
    private lateinit var notesManager: FieldNotesManager
    private lateinit var voiceMemos: VoiceMemoHelper
    private lateinit var favorites: FavoritesManager
    private lateinit var sessionHistory: SessionHistory
    private lateinit var fieldForms: FieldDataForms
    private lateinit var photoGeotag: PhotoGeotagHelper
    private lateinit var remoteLayerStore: RemoteMapLayerStore
    private lateinit var adaptiveCompute: AdaptiveComputeController
    private lateinit var localDemStore: LocalDemStore
    private var compass: CompassHelper? = null

    private val observers = mutableListOf<GeoPoint>()
    private val markers = mutableListOf<Marker>()
    private val polygons = mutableListOf<Polygon>()
    private val results = mutableListOf<ViewshedResult>()
    private val noteMarkers = mutableListOf<Marker>()
    private val memoMarkers = mutableListOf<Marker>()
    private val analysisOverlays = mutableListOf<Polygon>()
    private val analysisLines = mutableListOf<Polyline>()
    private val analysisCircles = mutableListOf<Circle>()
    private val analysisMarkers = mutableListOf<Marker>()
    private val measureMarkers = mutableListOf<Marker>()
    private val pathPoints = mutableListOf<GeoPoint>()
    private val remoteTileOverlays = mutableMapOf<String, TileOverlay>()
    private val pendingBackgroundResults = mutableListOf<ViewshedResult>()

    private var calcJob: Job? = null
    private var calculationVersion = 0
    /** Prevents slider ↔ text feedback loops while editing viewer height. */
    private var syncingHeight = false
    private var measureA: GeoPoint? = null
    private var measureLine: Polyline? = null
    private var pathMode = false
    private var pendingLocationObserver = false
    private var systemInsetLeft = 0
    private var systemInsetRight = 0
    private var localDemDisplayName: String? = null

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted =
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val shouldPlaceObserver = pendingLocationObserver
        pendingLocationObserver = false
        if (granted) {
            if (::map.isInitialized) {
                enableMyLocation(centerIfAvailable = !shouldPlaceObserver)
                if (shouldPlaceObserver) placeObserverAtMyLocation()
            }
        } else {
            toast("Location denied — Newburgh NY default.")
        }
    }

    private val audioPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleVoiceMemo() else toast("Microphone permission required for voice memos.")
    }

    private val pickPhotoRequest = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val loc = activeFieldPoint()
        val photo = photoGeotag.importAndGeotag(uri, loc)
        if (photo != null) toast("Photo geotagged @ ${"%.5f".format(loc.lat)}, ${"%.5f".format(loc.lon)}")
        else toast("Geotag failed")
    }

    /** Load a local GeoTIFF, ESRI ASCII Grid, or elevation CSV into the terrain engine. */
    private val pickDemRequest = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val name = contentDisplayName(uri)
                binding.tvTerrainStatus.text = "Importing $name into the offline DEM library…"
                activateStoredDem(localDemStore.importFromUri(uri, name))
            } catch (e: Exception) {
                Log.e("Viewshed", "DEM load failed", e)
                toast("DEM load failed: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applySaved(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        offlineCache = OfflineMapCache(this)
        notesManager = FieldNotesManager(this)
        voiceMemos = VoiceMemoHelper(this)
        favorites = FavoritesManager(this)
        sessionHistory = SessionHistory(this)
        fieldForms = FieldDataForms(this)
        photoGeotag = PhotoGeotagHelper(this)
        remoteLayerStore = RemoteMapLayerStore(this)
        adaptiveCompute = AdaptiveComputeController(this)
        localDemStore = LocalDemStore(this)
        compass = CompassHelper(this) { deg ->
            runOnUiThread {
                if (::binding.isInitialized) {
                    binding.tvCompass.text = getString(
                        R.string.compass_format,
                        deg,
                        compassCardinal(deg)
                    )
                }
            }
        }.also {
            if (it.available) it.start()
            else binding.tvCompass.text = "Compass: sensors unavailable"
        }

        // Initialize Maps before fragment async callback (avoids some tablet cold-start blanks)
        try {
            MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LATEST) { }
        } catch (e: Exception) {
            Log.w(TAG, "MapsInitializer", e)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        setupBottomSheetInsets()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupUi()
        refreshOfflineStatus()
    }

    override fun onDestroy() {
        adaptiveCompute.stop()
        compass?.stop()
        voiceMemos.release()
        super.onDestroy()
    }

    /**
     * Keep Calculate/Clear fully visible above system nav bar.
     * Peek height = measured height of handle → action buttons (not the scroll extras).
     */
    private fun setupBottomSheetInsets() {
        bottomSheetBehavior.isFitToContents = false
        bottomSheetBehavior.skipCollapsed = false
        bottomSheetBehavior.isHideable = false
        bottomSheetBehavior.halfExpandedRatio = 0.55f
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemInsetLeft = bars.left
            systemInsetRight = bars.right
            binding.bottomSheet.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom,
            )
            bottomSheetBehavior.expandedOffset = bars.top + dp(8)
            val topLp = binding.topBar.layoutParams as ViewGroup.MarginLayoutParams
            topLp.topMargin = bars.top + dp(12)
            topLp.leftMargin = bars.left + dp(12)
            topLp.rightMargin = bars.right + dp(12)
            binding.topBar.layoutParams = topLp
            val fabLp = binding.fabColumn.layoutParams as ViewGroup.MarginLayoutParams
            fabLp.rightMargin = bars.right + dp(16)
            binding.fabColumn.layoutParams = fabLp
            updateMapContentPadding()
            insets
        }

        binding.bottomSheet.post { updatePeekToActionButtons() }
        binding.topBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMapContentPadding()
        }
        binding.actionButtons.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePeekToActionButtons()
        }
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    binding.fabColumn.alpha = 1f
                    setFabsEnabled(true)
                } else if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    binding.fabColumn.alpha = 0f
                    setFabsEnabled(false)
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                val progress = slideOffset.coerceIn(0f, 1f)
                binding.fabColumn.alpha = (1f - progress * 2f).coerceIn(0f, 1f)
                setFabsEnabled(progress < 0.35f)
            }
        })
        ViewCompat.requestApplyInsets(binding.rootCoordinator)
    }

    private fun updatePeekToActionButtons() {
        if (!::bottomSheetBehavior.isInitialized) return
        val actions = binding.actionButtons
        val peek = actions.bottom + binding.bottomSheet.paddingBottom +
            (8 * resources.displayMetrics.density).toInt()
        if (peek > 0) {
            bottomSheetBehavior.peekHeight = peek
            bottomSheetBehavior.isFitToContents = false
            positionFabs(peek)
            updateMapContentPadding()
        }
    }

    private fun positionFabs(peekPx: Int) {
        val lp = binding.fabColumn.layoutParams as ViewGroup.MarginLayoutParams
        val gap = (12 * resources.displayMetrics.density).toInt()
        lp.bottomMargin = peekPx + gap
        binding.fabColumn.layoutParams = lp
    }

    private fun setFabsEnabled(enabled: Boolean) {
        binding.fabAddObserver.isEnabled = enabled
        binding.fabMyLocation.isEnabled = enabled
    }

    private fun updateMapContentPadding() {
        if (!::map.isInitialized || !::bottomSheetBehavior.isInitialized) return
        map.setPadding(
            systemInsetLeft,
            binding.topBar.bottom + dp(8),
            systemInsetRight,
            bottomSheetBehavior.peekHeight + dp(8),
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun setupUi() {
        val expandSheet = View.OnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        binding.sheetHandle.setOnClickListener(expandSheet)
        binding.tvHint.setOnClickListener(expandSheet)
        binding.btnCalculate.setOnClickListener { runCalculation() }
        binding.btnClear.setOnClickListener { clearAll() }
        binding.btnExportGeoJson.setOnClickListener { shareText(GeoExport.toGeoJson(results, multiObserver = binding.switchMultiObserver.isChecked), "application/geo+json", "Viewshed.geojson") }
        binding.btnExportKml.setOnClickListener { shareText(GeoExport.toKml(results), "application/vnd.google-earth.kml+xml", "Viewshed.kml") }
        binding.btnExportGpx.setOnClickListener { shareText(GeoExport.toGpx(results), "application/gpx+xml", "Viewshed.gpx") }
        binding.btnExportCsv.setOnClickListener { shareText(GeoExport.toCsv(results), "text/csv", "Viewshed.csv") }
        binding.btnExportMore.setOnClickListener { showMoreExports() }

        binding.btnSearch.setOnClickListener { searchPlace() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchPlace()
                true
            } else false
        }

        binding.fabMyLocation.setOnClickListener { placeObserverAtMyLocation() }
        binding.fabAddObserver.setOnClickListener {
            if (!::map.isInitialized) return@setOnClickListener
            val c = map.cameraPosition.target
            placeObserver(GeoPoint(c.latitude, c.longitude), clearOthers = !binding.switchMultiObserver.isChecked)
        }

        binding.chipHybrid.setOnClickListener { if (::map.isInitialized) map.mapType = GoogleMap.MAP_TYPE_HYBRID }
        binding.chipTerrain.setOnClickListener { if (::map.isInitialized) map.mapType = GoogleMap.MAP_TYPE_TERRAIN }
        binding.chipSatellite.setOnClickListener { if (::map.isInitialized) map.mapType = GoogleMap.MAP_TYPE_SATELLITE }
        binding.chipNormal.setOnClickListener { if (::map.isInitialized) map.mapType = GoogleMap.MAP_TYPE_NORMAL }

        binding.chipPresetTree.setOnClickListener { applyPreset(AnalysisPreset.TREE_STAND) }
        binding.chipPresetKayak.setOnClickListener { applyPreset(AnalysisPreset.KAYAK) }
        binding.chipPresetRidge.setOnClickListener { applyPreset(AnalysisPreset.RIDGE) }
        binding.chipPresetCustom.setOnClickListener { /* keep current fields */ }

        binding.btnSettings.setOnClickListener { showSettingsDialog() }

        setupViewerHeightControls()
        setupQualityAndExperimental()
        setupFieldTools()
        setupProAnalysis()
        setupTerrainEngine()
        setupCloudBackend()

        // Prefer real elevation when Maps key is baked in
        binding.switchDemoTerrain.isChecked = !BuildConfig.HAS_MAPS_API_KEY
        if (!BuildConfig.HAS_MAPS_API_KEY) {
            Log.w(TAG, getString(R.string.no_api_key_demo))
            binding.tvHint.text = getString(R.string.no_maps_key_hint)
        }
        // Probe optional native path once (never required)
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = VulkanViewshed.isAvailable()
            Log.i(TAG, "Vulkan experimental available=$ok")
        }

        setExportEnabled(false)
    }

    private fun setupProAnalysis() {
        binding.btnIntervis.setOnClickListener { runIntervisibility() }
        binding.btnCumulative.setOnClickListener { runCumulative() }
        binding.btnFrequency.setOnClickListener { runFrequency() }
        binding.btnShadow.setOnClickListener { runShadow() }
        binding.btnPathVis.setOnClickListener { togglePathMode() }
        binding.btnWeighted.setOnClickListener { runWeighted() }
        binding.btnFavorites.setOnClickListener { showFavoritesDialog() }
        binding.btnHistory.setOnClickListener { showHistoryDialog() }
        binding.btnFieldForm.setOnClickListener { promptFieldForm() }
        binding.btnGeotagPhoto.setOnClickListener { pickPhotoRequest.launch("image/*") }
    }

    private fun selectedElevSource(): ElevationRepository.ElevSource = when {
        binding.chipElevLocal.isChecked -> ElevationRepository.ElevSource.LOCAL_DEM
        binding.chipElevUsgs.isChecked -> ElevationRepository.ElevSource.USGS_3DEP
        binding.chipElevSrtm.isChecked -> ElevationRepository.ElevSource.SRTM
        binding.chipElevEtopo.isChecked -> ElevationRepository.ElevSource.ETOPO
        else -> ElevationRepository.ElevSource.GOOGLE
    }

    private fun setupTerrainEngine() {
        binding.btnTerrainLab.setOnClickListener {
            startActivity(Intent(this, TerrainLabActivity::class.java))
        }
        binding.btnLoadDem.setOnClickListener {
            pickDemRequest.launch(
                arrayOf(
                    "text/*",
                    "text/csv",
                    "text/plain",
                    "application/octet-stream",
                    "*/*",
                ),
            )
        }
        binding.btnRemoteLayers.setOnClickListener { showRemoteLayersDialog() }
        binding.btnManageDem.setOnClickListener { showDemLibrary() }
        binding.btnDemoDem.setOnClickListener {
            lifecycleScope.launch {
                val center =
                    observers.lastOrNull()
                        ?: GeoPoint(41.503, -74.01)
                val grid = withContext(Dispatchers.Default) {
                    TerrainEngine.generateDemoRegion(center = center)
                }
                elevationRepo.localTerrain = grid
                elevationRepo.localDemSource = null
                localDemDisplayName = grid.name
                TerrainWorkspace.current = withContext(Dispatchers.Default) {
                    TerrainRaster.from(grid)
                }
                binding.chipElevLocal.isChecked = true
                binding.switchDemoTerrain.isChecked = false
                updateTerrainStatus()
                toast("Demo DEM grid ready around ${"%.4f".format(center.lat)}, ${"%.4f".format(center.lon)}")
            }
        }
        binding.chipElevLocal.setOnCheckedChangeListener { _, checked ->
            if (checked && elevationRepo.localTerrain == null && elevationRepo.localDemSource == null) {
                // Auto-generate demo region so Local DEM always has a surface
                binding.btnDemoDem.performClick()
            }
            updateTerrainStatus()
        }
        updateTerrainStatus()
    }

    private fun updateTerrainStatus() {
        elevationRepo.localDemSource?.let { source ->
            val bounds = source.bounds
            binding.tvTerrainStatus.text = String.format(
                Locale.US,
                "Terrain: %s · %.5f,%.5f to %.5f,%.5f",
                localDemDisplayName ?: "GeoTIFF",
                bounds.south,
                bounds.west,
                bounds.north,
                bounds.east,
            )
            return
        }
        val t = elevationRepo.localTerrain
        if (t == null) {
            binding.tvTerrainStatus.text = getString(R.string.terrain_none)
            return
        }
        val s = t.stats()
        binding.tvTerrainStatus.text =
            getString(
                R.string.terrain_loaded,
                t.name,
                t.ncols,
                t.nrows,
                s.minElevM,
                s.maxElevM,
            )
    }

    private suspend fun activateStoredDem(entry: LocalDemStore.Entry) {
        val file = localDemStore.file(entry)
        val extension = file.extension.lowercase(Locale.US)
        if (extension in setOf("tif", "tiff", "asc", "ascii", "grd")) {
            val parsed = LocalDemLoader.load(file)
            val source = if (extension in setOf("asc", "ascii", "grd") && parsed is RasterDemSource) {
                withContext(Dispatchers.IO) {
                    MappedFloatDem.create(File(cacheDir, "mapped_dems/${entry.id}.float"), parsed)
                }
            } else parsed
            elevationRepo.localDemSource = source
            elevationRepo.localTerrain = null
            localDemDisplayName = entry.name
            (source as? RasterDemSource)?.let { rasterSource ->
                TerrainWorkspace.current = withContext(Dispatchers.Default) {
                    TerrainRaster.from(rasterSource, entry.name)
                }
            }
        } else {
            val grid = withContext(Dispatchers.IO) { TerrainEngine.loadAuto(file) }
            elevationRepo.localTerrain = grid
            elevationRepo.localDemSource = null
            localDemDisplayName = entry.name
            TerrainWorkspace.current = withContext(Dispatchers.Default) { TerrainRaster.from(grid) }
        }
        binding.chipElevLocal.isChecked = true
        binding.switchDemoTerrain.isChecked = false
        updateTerrainStatus()
        toast("Terrain loaded: ${entry.name}")
    }

    private fun showDemLibrary() {
        val entries = localDemStore.list()
        val labels = buildList {
            add("＋ Import DEM from device")
            add("↓ Download DEM from URL")
            entries.forEach { entry -> add("${entry.name} · ${formatBytes(entry.byteCount)}") }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Offline DEM library")
            .setMessage("Stored files remain available without network access. Tap a DEM to open or delete it.")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> binding.btnLoadDem.performClick()
                    1 -> promptDemDownload()
                    else -> showStoredDemActions(entries[which - 2])
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showStoredDemActions(entry: LocalDemStore.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setMessage("${formatBytes(entry.byteCount)} · ${entry.source.take(180)}")
            .setItems(arrayOf("Open", "Delete")) { _, which ->
                if (which == 0) {
                    lifecycleScope.launch {
                        try {
                            activateStoredDem(entry)
                        } catch (error: Exception) {
                            toast("DEM open failed: ${error.message}")
                        }
                    }
                } else {
                    localDemStore.delete(entry)
                    toast("${entry.name} deleted from offline storage")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptDemDownload() {
        val name = EditText(this).apply {
            hint = "Filename, including .tif or .asc"
            setSingleLine()
        }
        val url = EditText(this).apply {
            hint = "Direct HTTPS GeoTIFF / ASCII Grid URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), 0)
            addView(name)
            addView(url)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Download DEM")
            .setMessage("The direct file is copied into the offline DEM library (2 GiB maximum).")
            .setView(content)
            .setPositiveButton("Download") { _, _ ->
                lifecycleScope.launch {
                    try {
                        binding.tvTerrainStatus.text = "Downloading DEM…"
                        val entry = localDemStore.download(
                            url.text?.toString()?.trim().orEmpty(),
                            name.text?.toString()?.trim().orEmpty(),
                        )
                        activateStoredDem(entry)
                    } catch (error: Exception) {
                        updateTerrainStatus()
                        toast("DEM download failed: ${error.message}")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun contentDisplayName(uri: android.net.Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "terrain.tif"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun restoreRemoteLayers() {
        if (!::map.isInitialized || !::remoteLayerStore.isInitialized) return
        remoteLayerStore.list().forEach(::applyRemoteLayer)
    }

    private fun showRemoteLayersDialog() {
        val layers = remoteLayerStore.list()
        val items = buildList {
            add("＋ Add remote layer")
            layers.forEach { layer ->
                val state = if (remoteTileOverlays.containsKey(layer.id)) "visible" else "hidden"
                add("${layer.name} · ${layer.type.label} · $state")
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Remote terrain and map layers")
            .setMessage("Add public XYZ, ArcGIS MapServer, WMS, or WCS endpoints. Layers are saved on this device.")
            .setItems(items) { _, which ->
                if (which == 0) chooseRemoteLayerType()
                else showRemoteLayerActions(layers[which - 1])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseRemoteLayerType(existing: RemoteMapLayerSpec? = null) {
        if (existing != null) {
            promptRemoteLayer(existing.type, existing)
            return
        }
        val types = RemoteLayerType.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("Layer service type")
            .setItems(types.map { it.label }.toTypedArray()) { _, which ->
                promptRemoteLayer(types[which], null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptRemoteLayer(type: RemoteLayerType, existing: RemoteMapLayerSpec?) {
        val name = EditText(this).apply {
            hint = "Display name"
            setText(existing?.name.orEmpty())
            setSingleLine()
        }
        val url = EditText(this).apply {
            hint = when (type) {
                RemoteLayerType.XYZ -> "https://server/{z}/{x}/{y}.png"
                RemoteLayerType.ARCGIS -> "https://server/arcgis/rest/services/name/MapServer"
                RemoteLayerType.WMS -> "https://server/geoserver/wms"
                RemoteLayerType.WCS -> "https://server/geoserver/wcs"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url.orEmpty())
            setSingleLine()
        }
        val serviceLayer = EditText(this).apply {
            hint = if (type == RemoteLayerType.WCS) "Coverage identifier" else "Layer name"
            setText(existing?.layerName.orEmpty())
            setSingleLine()
            visibility = if (type == RemoteLayerType.WMS || type == RemoteLayerType.WCS) View.VISIBLE else View.GONE
        }
        val opacity = EditText(this).apply {
            hint = "Opacity 0.0–1.0"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", existing?.opacity ?: 0.75f))
            setSingleLine()
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val spacing = dp(12)
            setPadding(spacing, dp(4), spacing, 0)
            addView(name)
            addView(url)
            addView(serviceLayer)
            addView(opacity)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add ${type.label}" else "Edit ${existing.name}")
            .setMessage(
                if (type == RemoteLayerType.WCS) {
                    "WCS viewing requires a server that supports image/png GetCoverage responses."
                } else {
                    "Only add endpoints you trust; tile requests go directly from this device to that server."
                },
            )
            .setView(content)
            .setPositiveButton(R.string.save) { _, _ ->
                val spec = RemoteMapLayerSpec(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name.text?.toString()?.trim().orEmpty(),
                    type = type,
                    url = url.text?.toString()?.trim().orEmpty(),
                    layerName = serviceLayer.text?.toString()?.trim().orEmpty(),
                    opacity = (opacity.text?.toString()?.toFloatOrNull() ?: 0.75f).coerceIn(0f, 1f),
                )
                try {
                    RemoteMapLayers.validate(spec)
                    remoteLayerStore.save(spec)
                    applyRemoteLayer(spec)
                    toast("${spec.name} added to the map")
                } catch (error: IllegalArgumentException) {
                    toast(error.message ?: "Invalid layer settings")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRemoteLayerActions(spec: RemoteMapLayerSpec) {
        val visible = remoteTileOverlays.containsKey(spec.id)
        val actions = arrayOf(if (visible) "Hide layer" else "Show layer", "Edit", "Delete")
        MaterialAlertDialogBuilder(this)
            .setTitle(spec.name)
            .setMessage(spec.url)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> if (visible) {
                        remoteTileOverlays.remove(spec.id)?.remove()
                    } else {
                        applyRemoteLayer(spec)
                    }
                    1 -> chooseRemoteLayerType(spec)
                    2 -> {
                        remoteTileOverlays.remove(spec.id)?.remove()
                        remoteLayerStore.delete(spec.id)
                        toast("${spec.name} deleted")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyRemoteLayer(spec: RemoteMapLayerSpec) {
        if (!::map.isInitialized) return
        try {
            remoteTileOverlays.remove(spec.id)?.remove()
            val overlay = map.addTileOverlay(
                TileOverlayOptions()
                    .tileProvider(RemoteMapLayers.provider(spec))
                    .transparency((1f - spec.opacity).coerceIn(0f, 1f))
                    .fadeIn(true)
                    .zIndex(2f),
            )
            if (overlay != null) remoteTileOverlays[spec.id] = overlay
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Invalid remote map layer ${spec.name}", error)
        }
    }

    private fun compassCardinal(deg: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val i = ((deg + 22.5f) / 45f).toInt() % 8
        return dirs[i]
    }

    private fun setupFieldTools() {
        binding.btnFieldProjects.setOnClickListener {
            startActivity(Intent(this, FieldProjectActivity::class.java))
        }
        binding.btnTerrainAr.setOnClickListener {
            startActivity(Intent(this, ArTerrainActivity::class.java))
        }
        binding.btnCacheOffline.setOnClickListener { cacheOfflinePack() }
        binding.btnManageOffline.setOnClickListener { showManageOfflineDialog() }
        binding.btnAddNote.setOnClickListener { promptAddNote() }
        binding.btnListNotes.setOnClickListener { showNotesList() }
        binding.btnVoiceMemo.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                toggleVoiceMemo()
            } else {
                audioPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.btnListMemos.setOnClickListener { showMemosList() }
        binding.switchMeasureMode.setOnCheckedChangeListener { _, checked ->
            clearMeasurementOverlays()
            binding.tvMeasureResult.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) {
                binding.tvMeasureResult.text = "Measure: tap two points on the map"
                toast("Measure mode: tap map twice for distance")
            }
        }
        binding.switchOfflineOnly.setOnCheckedChangeListener { _, checked ->
            if (checked && offlineCache.listPacks().isEmpty()) {
                toast("Cache an offline pack first.")
                binding.switchOfflineOnly.isChecked = false
            }
        }
    }

    private fun setupQualityAndExperimental() {
        binding.chipQualityLow.setOnClickListener { applyQuality(SampleQuality.LOW) }
        binding.chipQualityMed.setOnClickListener { applyQuality(SampleQuality.MEDIUM) }
        binding.chipQualityHigh.setOnClickListener { applyQuality(SampleQuality.HIGH) }
        adaptiveCompute.start(::updateComputeHealth)
        updateComputeHealth(adaptiveCompute.snapshot())
        WorkManager.getInstance(this).getWorkInfosByTagLiveData(ViewshedComputeWorker.TAG)
            .observe(this) { work -> work.forEach(::handleBackgroundWork) }
    }

    private fun applyQuality(q: SampleQuality) {
        binding.etNumRays.setText(q.rays.toString())
        binding.etSampleSteps.setText(q.samples.toString())
        when (q) {
            SampleQuality.LOW -> binding.chipQualityLow.isChecked = true
            SampleQuality.MEDIUM -> binding.chipQualityMed.isChecked = true
            SampleQuality.HIGH -> binding.chipQualityHigh.isChecked = true
        }
        toast("${q.label}: ${q.rays} rays × ${q.samples} samples")
    }

    private fun selectedQuality(): SampleQuality = when {
        binding.chipQualityLow.isChecked -> SampleQuality.LOW
        binding.chipQualityHigh.isChecked -> SampleQuality.HIGH
        else -> SampleQuality.MEDIUM
    }

    private fun showSettingsDialog() {
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val modes = intArrayOf(
            ThemePreferences.MODE_SYSTEM,
            ThemePreferences.MODE_LIGHT,
            ThemePreferences.MODE_DARK
        )
        val current = ThemePreferences.getMode(this)
        val checked = modes.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme_dialog_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemePreferences.setMode(this, modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.settings_done, null)
            .show()
    }

    /**
     * Viewer (eye) height: always-visible slider, numeric field, ± buttons, quick chips.
     * Applied on every Calculate via [readParams].
     */
    private fun setupViewerHeightControls() {
        setViewerHeightM(1.7, fromUser = false)

        binding.sliderViewerHeight.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (!fromUser || syncingHeight) return@addOnChangeListener
            // Slider stores tenths of a metre (17 == 1.7 m).
            setViewerHeightM(value / 10.0, fromUser = true, source = HeightSource.SLIDER)
        }

        binding.btnHeightMinus.setOnClickListener {
            val next = (currentViewerHeightM() - HEIGHT_STEP_M).coerceAtLeast(0.0)
            setViewerHeightM(next, fromUser = true, source = HeightSource.BUTTON)
        }
        binding.btnHeightPlus.setOnClickListener {
            val next = (currentViewerHeightM() + HEIGHT_STEP_M).coerceAtMost(HEIGHT_MAX_M)
            setViewerHeightM(next, fromUser = true, source = HeightSource.BUTTON)
        }

        binding.etObserverHeight.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncingHeight) return
                val v = s?.toString()?.toDoubleOrNull() ?: return
                setViewerHeightM(v, fromUser = true, source = HeightSource.TEXT)
            }
        })
        binding.etObserverHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                setViewerHeightM(
                    currentViewerHeightM(),
                    fromUser = true,
                    source = HeightSource.BUTTON,
                )
            }
        }

        binding.chipHeightProne.setOnClickListener { setViewerHeightM(0.5, fromUser = true, source = HeightSource.CHIP) }
        binding.chipHeightStanding.setOnClickListener { setViewerHeightM(1.7, fromUser = true, source = HeightSource.CHIP) }
        binding.chipHeightTree.setOnClickListener { setViewerHeightM(5.0, fromUser = true, source = HeightSource.CHIP) }
        binding.chipHeightRoof.setOnClickListener { setViewerHeightM(12.0, fromUser = true, source = HeightSource.CHIP) }
        binding.chipHeightTower.setOnClickListener { setViewerHeightM(20.0, fromUser = true, source = HeightSource.CHIP) }
    }

    private fun currentViewerHeightM(): Double =
        binding.etObserverHeight.text?.toString()?.toDoubleOrNull()
            ?: (binding.sliderViewerHeight.value / 10.0).toDouble()

    private fun setViewerHeightM(
        meters: Double,
        fromUser: Boolean,
        source: HeightSource = HeightSource.TEXT
    ) {
        val clamped = meters.coerceIn(0.0, HEIGHT_MAX_M)
        // Round to 0.1 m for clean display / slider steps
        val rounded = (clamped * 10.0).roundToInt() / 10.0
        syncingHeight = true
        try {
            if (source != HeightSource.TEXT) {
                val text = if (rounded == rounded.toLong().toDouble()) {
                    rounded.toLong().toString()
                } else {
                    String.format(Locale.US, "%.1f", rounded)
                }
                if (binding.etObserverHeight.text?.toString() != text) {
                    binding.etObserverHeight.setText(text)
                    binding.etObserverHeight.setSelection(text.length)
                }
            }
            if (source != HeightSource.SLIDER) {
                val cm = (rounded * 10.0).roundToInt().toFloat().coerceIn(
                    binding.sliderViewerHeight.valueFrom,
                    binding.sliderViewerHeight.valueTo
                )
                if (kotlin.math.abs(binding.sliderViewerHeight.value - cm) >= 0.5f) {
                    binding.sliderViewerHeight.value = cm
                }
            }
            updateHeightQuickChips(rounded)
            updateObserverMarkerSnippets(rounded)
        } finally {
            syncingHeight = false
        }
        if (fromUser) {
            binding.chipPresetCustom.isChecked = true
        }
    }

    private fun updateHeightQuickChips(meters: Double) {
        binding.chipHeightProne.isChecked = kotlin.math.abs(meters - 0.5) < 0.05
        binding.chipHeightStanding.isChecked = kotlin.math.abs(meters - 1.7) < 0.05
        binding.chipHeightTree.isChecked = kotlin.math.abs(meters - 5.0) < 0.05
        binding.chipHeightRoof.isChecked = kotlin.math.abs(meters - 12.0) < 0.05
        binding.chipHeightTower.isChecked = kotlin.math.abs(meters - 20.0) < 0.05
    }

    private fun updateObserverMarkerSnippets(eyeM: Double) {
        markers.forEachIndexed { index, marker ->
            marker.snippet = "Eye height: ${String.format(Locale.US, "%.1f", eyeM)} m"
            marker.title = "Observer ${index + 1}"
        }
    }

    private enum class HeightSource { SLIDER, TEXT, BUTTON, CHIP }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.mapType = GoogleMap.MAP_TYPE_HYBRID
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMapToolbarEnabled = false
        updateMapContentPadding()

        map.setOnMapClickListener { latLng ->
            val p = GeoPoint(latLng.latitude, latLng.longitude)
            when {
                binding.switchMeasureMode.isChecked -> handleMeasureTap(p)
                pathMode -> addPathPoint(p)
            }
        }

        map.setOnMapLongClickListener { latLng ->
            val p = GeoPoint(latLng.latitude, latLng.longitude)
            if (binding.switchMeasureMode.isChecked) {
                handleMeasureTap(p)
                return@setOnMapLongClickListener
            }
            if (pathMode) {
                addPathPoint(p)
                return@setOnMapLongClickListener
            }
            placeObserver(p, clearOthers = !binding.switchMultiObserver.isChecked)
        }

        map.setOnMarkerClickListener { marker ->
            val noteId = marker.tag as? String
            if (noteId != null && noteId.startsWith("note:")) {
                showNoteDetail(noteId.removePrefix("note:"))
                true
            } else if (noteId != null && noteId.startsWith("memo:")) {
                playMemoById(noteId.removePrefix("memo:"))
                true
            } else {
                false
            }
        }

        // Default camera first; GPS may override — never overwrite GPS with Newburgh later.
        moveToDefaultLocation()
        refreshFieldMarkers()
        restoreRemoteLayers()
        pendingBackgroundResults.toList().forEach(::displayBackgroundResult)
        pendingBackgroundResults.clear()

        if (hasLocationPermission()) {
            enableMyLocation(centerIfAvailable = true)
        } else {
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun moveToDefaultLocation() {
        if (!::map.isInitialized) return
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(NEWBURGH, 12f))
        } catch (e: Exception) {
            Log.w(TAG, "moveCamera failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation(centerIfAvailable: Boolean = true) {
        // Both guarded calls accept either fine or coarse location permission.
        if (!hasLocationPermission()) return
        try {
            map.isMyLocationEnabled = true
        } catch (e: Exception) {
            Log.w(TAG, "myLocation", e)
            return
        }
        if (!centerIfAvailable) return
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                location?.let {
                    try {
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(it.latitude, it.longitude),
                                13f
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "animateCamera", e)
                    }
                }
            }
            .addOnFailureListener { Log.w(TAG, "lastLocation failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun placeObserverAtMyLocation() {
        if (!hasLocationPermission()) {
            pendingLocationObserver = true
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location == null) {
                toast("No location fix yet — try again outdoors.")
                return@addOnSuccessListener
            }
            placeObserver(
                GeoPoint(location.latitude, location.longitude),
                clearOthers = !binding.switchMultiObserver.isChecked
            )
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(location.latitude, location.longitude),
                    14f
                )
            )
        }.addOnFailureListener { error ->
            Log.w(TAG, "lastLocation failed", error)
            toast("Location unavailable — check GPS and try again.")
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun placeObserver(point: GeoPoint, clearOthers: Boolean) {
        if (!::map.isInitialized) return
        if (clearOthers) {
            clearOverlaysOnly()
            observers.clear()
        }
        observers.add(point)
        val hue = MARKER_HUES[(observers.size - 1) % MARKER_HUES.size]
        val marker = map.addMarker(
            MarkerOptions()
                .position(LatLng(point.lat, point.lon))
                .title("Observer ${observers.size}")
                .snippet("Eye: ${binding.etObserverHeight.text} m")
                .icon(BitmapDescriptorFactory.defaultMarker(hue))
        )
        marker?.let { markers.add(it) }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 14f))
        toast("Observer ${observers.size} placed. Tap Calculate.")
    }

    private fun applyPreset(preset: AnalysisPreset) {
        val p = preset.toParams()
        setViewerHeightM(p.eyeHeightM, fromUser = false, source = HeightSource.CHIP)
        binding.etTargetHeight.setText(p.targetHeightM.toString())
        binding.etMaxDistance.setText(p.maxDistKm.toString())
        binding.etNumRays.setText(p.numRays.toString())
        binding.etSampleSteps.setText(p.samplesPerRay.toString())
        when (preset) {
            AnalysisPreset.TREE_STAND -> binding.chipPresetTree.isChecked = true
            AnalysisPreset.KAYAK -> binding.chipPresetKayak.isChecked = true
            AnalysisPreset.RIDGE -> binding.chipPresetRidge.isChecked = true
            AnalysisPreset.CUSTOM -> binding.chipPresetCustom.isChecked = true
        }
        toast("${preset.label} preset applied (viewer ${p.eyeHeightM} m)")
    }

    private fun readParams(): ViewshedParams {
        val raw = ViewshedParams(
            eyeHeightM = currentViewerHeightM(),
            targetHeightM = binding.etTargetHeight.text?.toString()?.toDoubleOrNull() ?: 0.0,
            maxDistKm = binding.etMaxDistance.text?.toString()?.toDoubleOrNull() ?: 5.0,
            numRays = binding.etNumRays.text?.toString()?.toIntOrNull() ?: 72,
            samplesPerRay = binding.etSampleSteps.text?.toString()?.toIntOrNull() ?: 80,
            useDemoTerrain = binding.switchDemoTerrain.isChecked,
            useCurvature = binding.switchCurvature.isChecked,
            refraction = binding.etRefraction.text?.toString()?.toDoubleOrNull() ?: 0.13,
            parallelRays = binding.switchParallel.isChecked,
            adaptiveSampling = false,
            binarySearchHorizon = false,
            quality = selectedQuality(),
        ).sanitized()
        if (!binding.switchAdaptiveQuality.isChecked) {
            updateComputeHealth(adaptiveCompute.snapshot())
            return raw
        }
        val decision = adaptiveCompute.adapt(raw)
        updateComputeHealth(decision.health, decision.reason)
        return decision.params
    }

    private fun setupCloudBackend() {
        val prefs = getSharedPreferences("viewshed_prefs", MODE_PRIVATE)
        val defaultUrl = getString(R.string.backend_url_default)
        val saved = prefs.getString("backend_url", defaultUrl)
        binding.etBackendUrl.setText(
            if (!saved.isNullOrBlank()) saved else defaultUrl,
        )
        // Local/on-device analysis is the reliable default. Cloud remains opt-in.
        binding.switchCloudBackend.isChecked = prefs.getBoolean("use_cloud_backend", false)
        if (!prefs.contains("backend_url")) {
            prefs.edit()
                .putString("backend_url", defaultUrl)
                .putBoolean("use_cloud_backend", false)
                .apply()
        }
        binding.btnTestBackend.setOnClickListener {
            val url =
                BackendViewshedClient.normalizeUrl(
                    binding.etBackendUrl.text?.toString().orEmpty(),
                )
            if (url.length < 10) {
                toast("Enter backend URL like http://YOUR_IP:8000")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                try {
                    val ok = BackendViewshedClient(url).health()
                    if (ok) {
                        prefs.edit()
                            .putString("backend_url", url)
                            .putBoolean("use_cloud_backend", true)
                            .apply()
                        binding.switchCloudBackend.isChecked = true
                        binding.etBackendUrl.setText(url)
                        toast("Backend OK: $url")
                    } else {
                        toast("Backend health failed")
                    }
                } catch (e: Exception) {
                    toast("Backend unreachable: ${e.message}")
                }
            }
        }
        binding.btnCollaboration.setOnClickListener {
            startActivity(Intent(this, CollaborationActivity::class.java))
        }
        binding.switchCloudBackend.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("use_cloud_backend", checked).apply()
            binding.etBackendUrl.text?.toString()?.let {
                prefs.edit().putString("backend_url", BackendViewshedClient.normalizeUrl(it)).apply()
            }
        }
    }

    private fun runCalculation() {
        if (observers.isEmpty()) {
            toast("Long-press the map to place an observer first.")
            return
        }
        if (!::map.isInitialized) {
            toast("Map not ready yet.")
            return
        }
        val version = ++calculationVersion
        calcJob?.cancel()
        calcJob = lifecycleScope.launch {
            try {
                setCalculating(true)
                val params = readParams()
                val multi = binding.switchMultiObserver.isChecked
                val targets = if (multi) observers.toList() else listOf(observers.last())
                val useCloud = binding.switchCloudBackend.isChecked
                val backendUrl =
                    BackendViewshedClient.normalizeUrl(
                        binding.etBackendUrl.text?.toString().orEmpty(),
                    )
                var completedWithCloud = useCloud
                var cloudFallbackNotified = false

                // Always redraw this run's polygons (avoid stacking duplicates)
                polygons.forEach { it.remove() }
                polygons.clear()
                results.clear()

                if (binding.switchBackgroundProcessing.isChecked) {
                    queueBackgroundCalculations(targets, params)
                    toast("${targets.size} durable analysis job(s) queued")
                    return@launch
                }

                val newResults = mutableListOf<ViewshedResult>()
                targets.forEachIndexed { index, observer ->
                    binding.tvProgress.visibility = View.VISIBLE
                    binding.tvProgress.text = "Observer ${index + 1}/${targets.size}…"

                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val result =
                        if (completedWithCloud && useCloud && backendUrl.length >= 10) {
                            binding.tvProgress.text = "Cloud backend…"
                            binding.progressBar.isIndeterminate = true
                            try {
                                BackendViewshedClient(backendUrl).compute(observer, params)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Log.w(TAG, "Cloud calculation failed; using on-device engine", error)
                                completedWithCloud = false
                                binding.switchCloudBackend.isChecked = false
                                if (!cloudFallbackNotified) {
                                    toast("Cloud unavailable — continuing on device.")
                                    cloudFallbackNotified = true
                                }
                                computeOnDevice(observer, params)
                            } finally {
                                binding.progressBar.isIndeterminate = false
                            }
                        } else {
                            completedWithCloud = false
                            computeOnDevice(observer, params)
                        }
                    PerformanceMonitor.record(
                        "viewshed",
                        android.os.SystemClock.elapsedRealtime() - t0,
                        rayCount = params.numRays,
                        sampleCount = result.stats.totalCells,
                    )
                    newResults.add(result)
                    if (::map.isInitialized) drawResult(result, index)
                }

                results.addAll(newResults)
                showStats(results)
                setExportEnabled(
                    results.any { it.visibleSectors.isNotEmpty() || it.boundary.size >= 3 },
                )
                binding.tvPerf.text = "Perf: ${PerformanceMonitor.summary()}"
                newResults.lastOrNull()?.let { last ->
                    try {
                        val file = java.io.File(filesDir, "last_session.json")
                        AnalysisSessionManager.save(AnalysisSession.fromResult(last), file)
                        sessionHistory.add(last)
                        val w = ProfessionalAnalysis.weightedStats(last)
                        binding.tvProStatus.text =
                            "Weighted score ${"%.2f".format(w.weightedScore)} km²-eq · near ${"%.2f".format(w.nearAreaKm2)} · far ${"%.2f".format(w.farAreaKm2)}"
                    } catch (e: Exception) {
                        Log.w(TAG, "session save failed", e)
                    }
                }
                val mode = when {
                    completedWithCloud -> "cloud"
                    params.useDemoTerrain -> "demo"
                    binding.switchOfflineOnly.isChecked -> "offline pack"
                    else -> selectedElevSource().name
                }
                toast("Viewshed ready — ${results.size} observer(s) · $mode")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Calculation error", e)
                toast("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (version == calculationVersion) {
                    setCalculating(false)
                    calcJob = null
                }
            }
        }
    }

    private suspend fun computeOnDevice(
        observer: GeoPoint,
        params: ViewshedParams,
    ): ViewshedResult {
        val samples = ViewshedEngine.samplePoints(observer, params)
        val elevations = resolveOnDeviceElevations(samples, params)
        return ViewshedEngine.compute(
            observer = observer,
            params = params,
            elevations = elevations,
            onRayProgress = { done, total ->
                withContext(Dispatchers.Main.immediate) {
                    val pct = if (total > 0) (100.0 * done / total).toInt() else 0
                    binding.progressBar.progress = pct
                    binding.tvProgress.text =
                        getString(R.string.progress_format, done, total, pct)
                }
            },
        )
    }

    private suspend fun queueBackgroundCalculations(
        targets: List<GeoPoint>,
        params: ViewshedParams,
    ) {
        val manager = WorkManager.getInstance(this)
        targets.forEachIndexed { index, observer ->
            binding.tvProgress.visibility = View.VISIBLE
            binding.progressBar.isIndeterminate = true
            binding.tvProgress.text = "Preparing background job ${index + 1}/${targets.size}…"
            val points = ViewshedEngine.samplePoints(observer, params)
            val elevations = resolveOnDeviceElevations(points, params)
            val request = ViewshedComputeWorker.prepare(this, observer, params, points, elevations)
            manager.enqueue(request)
        }
        binding.progressBar.isIndeterminate = false
        binding.tvProgress.text = "Background analysis queued · safe to leave this screen"
    }

    private fun handleBackgroundWork(work: WorkInfo) {
        when (work.state) {
            WorkInfo.State.RUNNING -> {
                val progress = work.progress.getInt(ViewshedComputeWorker.KEY_PROGRESS, 0)
                binding.tvProgress.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = progress
                binding.tvProgress.text = "Background terrain analysis · $progress%"
            }
            WorkInfo.State.SUCCEEDED -> {
                val preferences = getSharedPreferences("background_viewsheds", MODE_PRIVATE)
                val handled = preferences.getStringSet("handled", emptySet()).orEmpty()
                if (work.id.toString() in handled) return
                val result = ViewshedComputeWorker.readResult(this, work.outputData) ?: return
                preferences.edit().putStringSet("handled", handled + work.id.toString()).apply()
                if (::map.isInitialized) displayBackgroundResult(result)
                else pendingBackgroundResults += result
            }
            WorkInfo.State.FAILED -> {
                val error = work.outputData.getString(ViewshedComputeWorker.KEY_ERROR).orEmpty()
                binding.tvProgress.text = "Background analysis failed${if (error.isBlank()) "" else ": $error"}"
            }
            else -> Unit
        }
    }

    private fun displayBackgroundResult(result: ViewshedResult) {
        results += result
        drawResult(result, results.lastIndex)
        showStats(results)
        setExportEnabled(true)
        binding.tvProgress.visibility = View.GONE
        binding.tvPerf.text = "Perf: background worker · ${result.stats.visibleCells}/${result.stats.totalCells} visible cells"
        toast("Background viewshed ready")
    }

    private fun updateComputeHealth(health: ComputeHealth, adjustment: String = "") {
        if (!::binding.isInitialized) return
        val battery = if (health.batteryPercent >= 0) "${health.batteryPercent}%" else "unknown"
        val suffix = if (adjustment.isBlank()) "" else " · capped for $adjustment"
        binding.tvComputeStatus.text =
            "Compute: ${health.processors} cores · ${health.memoryClassMb} MB · thermal ${health.thermal.label} · battery $battery$suffix"
    }

    private suspend fun resolveOnDeviceElevations(
        points: List<GeoPoint>,
        params: ViewshedParams,
    ): ElevationGrid {
        val offlineOnly = binding.switchOfflineOnly.isChecked
        val source = selectedElevSource()
        return withContext(Dispatchers.IO) {
            elevationRepo.resolveElevations(
                points = points,
                useDemo = params.useDemoTerrain,
                offline = offlineCache,
                offlineOnly = offlineOnly,
                source = source,
            )
        }
    }

    private fun drawResult(result: ViewshedResult, colorIndex: Int) {
        val visibleBoundaries =
            result.visibleSectors.map { it.boundary }
                .ifEmpty { listOf(result.boundary) }
                .filter { it.size >= 3 }
        if (visibleBoundaries.isEmpty()) return
        try {
            val fill = FILL_COLORS[colorIndex % FILL_COLORS.size]
            val stroke = STROKE_COLORS[colorIndex % STROKE_COLORS.size]
            visibleBoundaries.forEach { boundary ->
                polygons.add(
                    map.addPolygon(
                        PolygonOptions()
                            .addAll(boundary.map { LatLng(it.lat, it.lon) })
                            .fillColor(fill)
                            .strokeColor(stroke)
                            .strokeWidth(1f)
                            .geodesic(true),
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "drawResult", e)
        }
    }

    private fun showStats(list: List<ViewshedResult>) {
        if (list.isEmpty()) {
            binding.tvStats.visibility = View.GONE
            return
        }
        val maxKm = list.maxOf { it.stats.maxRangeKm }
        val avgKm = list.map { it.stats.avgRangeKm }.average()
        val area = list.sumOf { it.stats.areaKm2 }
        val rays = list.sumOf { it.stats.numRays }
        binding.tvStats.visibility = View.VISIBLE
        binding.tvStats.text = getString(R.string.stats_format, maxKm, avgKm, area, rays)
    }

    private fun searchPlace() {
        val query = binding.etSearch.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            toast("Enter an address or place name.")
            return
        }
        lifecycleScope.launch {
            try {
                val geo = withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                    if (Build.VERSION.SDK_INT >= 33) {
                        // sync fallback for simplicity
                        geocoder.getFromLocationName(query, 1)
                    } else {
                        geocoder.getFromLocationName(query, 1)
                    }
                }
                val hit = geo?.firstOrNull()
                if (hit == null) {
                    toast("No results for “$query”.")
                    return@launch
                }
                val point = GeoPoint(hit.latitude, hit.longitude)
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 14f)
                )
                placeObserver(point, clearOthers = !binding.switchMultiObserver.isChecked)
            } catch (e: Exception) {
                Log.e(TAG, "Geocode failed", e)
                toast("Search failed: ${e.message}")
            }
        }
    }

    private fun clearOverlaysOnly() {
        polygons.forEach { it.remove() }
        polygons.clear()
        markers.forEach { it.remove() }
        markers.clear()
        results.clear()
    }

    private fun clearAll() {
        calculationVersion++
        calcJob?.cancel()
        calcJob = null
        clearOverlaysOnly()
        clearAnalysisOverlays()
        clearMeasurementOverlays()
        observers.clear()
        pathPoints.clear()
        pathMode = false
        binding.btnPathVis.text = getString(R.string.btn_path_vis)
        binding.tvStats.visibility = View.GONE
        binding.tvProStatus.text = getString(R.string.pro_status_hint)
        setExportEnabled(false)
        setCalculating(false)
        toast("Cleared. Long-press to place a new observer.")
    }

    private fun setCalculating(active: Boolean) {
        binding.btnCalculate.isEnabled = !active
        binding.btnCalculate.text = if (active) "Calculating…" else getString(R.string.calculate_viewshed)
        binding.progressBar.visibility = if (active) View.VISIBLE else View.GONE
        binding.tvProgress.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) binding.progressBar.progress = 0
    }

    private fun setExportEnabled(enabled: Boolean) {
        binding.btnExportGeoJson.isEnabled = enabled
        binding.btnExportKml.isEnabled = enabled
        binding.btnExportGpx.isEnabled = enabled
        binding.btnExportCsv.isEnabled = enabled
        binding.btnExportMore.isEnabled = enabled
    }

    private fun clearAnalysisOverlays() {
        analysisOverlays.forEach { it.remove() }
        analysisOverlays.clear()
        analysisLines.forEach { it.remove() }
        analysisLines.clear()
        analysisCircles.forEach { it.remove() }
        analysisCircles.clear()
        analysisMarkers.forEach { it.remove() }
        analysisMarkers.clear()
    }

    private fun runIntervisibility() {
        if (observers.size < 2) {
            toast("Place at least 2 observers (enable multi-observer).")
            return
        }
        lifecycleScope.launch {
            try {
                setCalculating(true)
                val params = readParams()
                val pts = observers.toList()
                val intervisibilitySamples = 60
                val samples =
                    pts.flatMapIndexed { index, from ->
                        pts.drop(index + 1).flatMap { to ->
                            ProfessionalAnalysis.intervisibilitySamplePoints(
                                from,
                                to,
                                intervisibilitySamples,
                            )
                        }
                    }
                        .distinctBy { it.key() }
                val elev = resolveOnDeviceElevations(samples, params)
                val matrix = withContext(Dispatchers.Default) {
                    ProfessionalAnalysis.multiObserverMatrix(
                        pts,
                        elev,
                        params.eyeHeightM,
                        intervisibilitySamples,
                        params.useCurvature,
                        params.refraction,
                    )
                }
                clearAnalysisOverlays()
                if (::map.isInitialized) {
                    for (i in pts.indices) {
                        for (j in i + 1 until pts.size) {
                            val visible = matrix[i][j]
                            val line = map.addPolyline(
                                PolylineOptions()
                                    .add(LatLng(pts[i].lat, pts[i].lon), LatLng(pts[j].lat, pts[j].lon))
                                    .color(if (visible) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
                                    .width(5f)
                            )
                            analysisLines.add(line)
                        }
                    }
                }
                val summary = ProfessionalAnalysis.matrixSummary(matrix)
                binding.tvProStatus.text = "Intervisibility: $summary"
                toast(summary)
            } catch (e: Exception) {
                Log.e(TAG, "intervis", e)
                toast("Intervis failed: ${e.message}")
            } finally {
                setCalculating(false)
            }
        }
    }

    private fun runCumulative() {
        if (results.isEmpty()) {
            toast("Calculate viewsheds first (multi-observer recommended).")
            return
        }
        lifecycleScope.launch {
            try {
                setCalculating(true)
                val cum = withContext(Dispatchers.Default) {
                    ProfessionalAnalysis.cumulativeViewshed(results, gridSteps = 24)
                }
                clearAnalysisOverlays()
                drawFrequencyCells(cum.cells, cum.maxCount)
                val msg = "Cumulative ≈ ${"%.2f".format(cum.unionApproxKm2)} km² · max overlap ${cum.maxCount} · ${cum.observerCount} observers"
                binding.tvProStatus.text = msg
                toast(msg)
            } catch (e: Exception) {
                toast("Cumulative failed: ${e.message}")
            } finally {
                setCalculating(false)
            }
        }
    }

    private fun runFrequency() = runCumulative()

    private fun drawFrequencyCells(cells: List<ProfessionalAnalysis.FrequencyCell>, maxCount: Int) {
        if (!::map.isInitialized || cells.isEmpty()) return
        val step = maxOf(1, cells.size / 320)
        val radiusM =
            (results.maxOfOrNull { it.params.maxDistKm * 1000.0 } ?: 1000.0)
                .div(26.0)
                .coerceIn(20.0, 500.0)
        cells.forEachIndexed { idx, cell ->
            if (idx % step != 0) return@forEachIndexed
            val t = if (maxCount > 0) cell.count.toFloat() / maxCount else 0f
            val fill =
                Color.argb(
                    112,
                    (48 + 207 * t).roundToInt(),
                    (110 - 54 * t).roundToInt(),
                    (220 - 180 * t).roundToInt(),
                )
            analysisCircles.add(
                map.addCircle(
                    CircleOptions()
                        .center(LatLng(cell.point.lat, cell.point.lon))
                        .radius(radiusM)
                        .fillColor(fill)
                        .strokeColor(Color.TRANSPARENT)
                        .strokeWidth(0f),
                ),
            )
        }
    }

    private fun runShadow() {
        val observer = observers.lastOrNull()
        if (observer == null) {
            toast("Place an observer first.")
            return
        }
        lifecycleScope.launch {
            try {
                setCalculating(true)
                val params = readParams()
                val samples = ViewshedEngine.samplePoints(observer, params)
                val elev = resolveOnDeviceElevations(samples, params)
                val shadow = withContext(Dispatchers.Default) {
                    ProfessionalAnalysis.shadowAnalysis(observer, elev, params)
                }
                clearAnalysisOverlays()
                if (!shadow.sunUp) {
                    binding.tvProStatus.text = "Sun is below horizon — no shadow polygon"
                    toast("Sun below horizon")
                    return@launch
                }
                if (::map.isInitialized && shadow.shadowBoundary.size >= 3) {
                    val poly = map.addPolygon(
                        PolygonOptions()
                            .addAll(shadow.shadowBoundary.map { LatLng(it.lat, it.lon) })
                            .fillColor(0x55424242)
                            .strokeColor(0xFFFFC107.toInt())
                            .strokeWidth(3f)
                            .geodesic(true)
                    )
                    analysisOverlays.add(poly)
                }
                val s = shadow.solar
                val msg = "Sun az ${"%.0f".format(s.azimuthDeg)}° alt ${"%.1f".format(s.altitudeDeg)}° · lit footprint drawn"
                binding.tvProStatus.text = msg
                toast(msg)
            } catch (e: Exception) {
                toast("Shadow failed: ${e.message}")
            } finally {
                setCalculating(false)
            }
        }
    }

    private fun togglePathMode() {
        pathMode = !pathMode
        if (pathMode) {
            clearAnalysisOverlays()
            pathPoints.clear()
            toast("Path LOS: tap path points, tap Path LOS again to run")
            binding.tvProStatus.text = "Path mode ON — tap map to add vertices"
            binding.btnPathVis.text = "Run path"
        } else {
            if (pathPoints.size < 2) {
                toast("Need 2+ path points")
                binding.btnPathVis.text = getString(R.string.btn_path_vis)
                pathPoints.clear()
                clearAnalysisOverlays()
                return
            }
            runPathVisibility()
            binding.btnPathVis.text = getString(R.string.btn_path_vis)
        }
    }

    private fun addPathPoint(p: GeoPoint) {
        pathPoints.add(p)
        if (::map.isInitialized) {
            val m = map.addMarker(
                MarkerOptions()
                    .position(LatLng(p.lat, p.lon))
                    .title("Path ${pathPoints.size}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
            m?.let { analysisMarkers.add(it) }
        }
        binding.tvProStatus.text = "Path points: ${pathPoints.size}"
    }

    private fun runPathVisibility() {
        val observer = observers.lastOrNull()
        if (observer == null) {
            toast("Place an observer first.")
            return
        }
        val path = pathPoints.toList()
        lifecycleScope.launch {
            try {
                setCalculating(true)
                val params = readParams()
                val samplesPerSegment = 24
                val samples =
                    ProfessionalAnalysis.pathVisibilitySamplePoints(
                        observer,
                        path,
                        samplesPerSegment,
                    )
                val elev = resolveOnDeviceElevations(samples, params)
                val res = withContext(Dispatchers.Default) {
                    ProfessionalAnalysis.pathVisibility(
                        observer, path, elev,
                        params.eyeHeightM, params.targetHeightM,
                        samplesPerSegment, params.useCurvature, params.refraction
                    )
                }
                if (::map.isInitialized) {
                    for (index in 0 until path.lastIndex) {
                        val visible = res.segmentVisible.getOrElse(index) { false }
                        analysisLines.add(
                            map.addPolyline(
                                PolylineOptions()
                                    .add(
                                        LatLng(path[index].lat, path[index].lon),
                                        LatLng(path[index + 1].lat, path[index + 1].lon),
                                    )
                                    .color(
                                        if (visible) 0xFF4CAF50.toInt()
                                        else 0xFFF44336.toInt(),
                                    )
                                    .width(7f),
                            ),
                        )
                    }
                }
                val msg = "Path LOS ${"%.0f".format(res.visibleFraction * 100)}% visible · " +
                    "${"%.0f".format(res.visibleLengthM)} / ${"%.0f".format(res.totalLengthM)} m"
                binding.tvProStatus.text = msg
                toast(msg)
            } catch (e: Exception) {
                toast("Path LOS failed: ${e.message}")
            } finally {
                setCalculating(false)
                pathPoints.clear()
            }
        }
    }

    private fun runWeighted() {
        if (results.isEmpty()) {
            toast("Calculate a viewshed first.")
            return
        }
        val lines = results.mapIndexed { i, r ->
            val w = ProfessionalAnalysis.weightedStats(r)
            "Obs ${i + 1}: score ${"%.2f".format(w.weightedScore)} · near ${"%.2f".format(w.nearAreaKm2)} · far ${"%.2f".format(w.farAreaKm2)} km²"
        }
        binding.tvProStatus.text = lines.joinToString(" · ")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_weighted)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.settings_done, null)
            .show()
    }

    private fun showFavoritesDialog() {
        val items = favorites.list()
        val labels = mutableListOf(getString(R.string.add_favorite))
        labels.addAll(items.map { "${it.name} (${"%.4f".format(it.lat)}, ${"%.4f".format(it.lon)})" })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_favorites)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    val input = EditText(this).apply {
                        hint = getString(R.string.favorite_name)
                        setPadding(48, 32, 48, 32)
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.add_favorite)
                        .setView(input)
                        .setPositiveButton(R.string.save) { _, _ ->
                            val name = input.text?.toString().orEmpty().ifBlank { "Favorite" }
                            favorites.add(activeFieldPoint(), name)
                            toast("Saved: $name")
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    val fav = items[which - 1]
                    if (::map.isInitialized) {
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(fav.lat, fav.lon), 14f)
                        )
                    }
                    placeObserver(fav.location, clearOthers = !binding.switchMultiObserver.isChecked)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHistoryDialog() {
        val items = sessionHistory.list()
        if (items.isEmpty()) {
            toast("No history yet — run Calculate.")
            return
        }
        val labels = items.map {
            "${it.label} · ${"%.2f".format(it.areaKm2)} km² · ${"%.4f".format(it.lat)},${"%.4f".format(it.lon)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_history)
            .setItems(labels) { _, which ->
                val h = items[which]
                if (::map.isInitialized) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(h.lat, h.lon), 13f)
                    )
                }
                placeObserver(GeoPoint(h.lat, h.lon), clearOthers = true)
                setViewerHeightM(h.eyeHeightM, fromUser = false)
                binding.etMaxDistance.setText(h.maxDistKm.toString())
            }
            .setNeutralButton("Clear") { _, _ ->
                sessionHistory.clear()
                toast("History cleared")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptFieldForm() {
        val point = activeFieldPoint()
        val site = EditText(this).apply { hint = "Site name"; setPadding(48, 16, 48, 8) }
        val weather = EditText(this).apply { hint = "Weather"; setPadding(48, 8, 48, 8) }
        val conditions = EditText(this).apply { hint = "Conditions"; setPadding(48, 8, 48, 8) }
        val notes = EditText(this).apply {
            hint = "Notes"
            minLines = 2
            setPadding(48, 8, 48, 16)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(site)
            addView(weather)
            addView(conditions)
            addView(notes)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_field_form)
            .setMessage(String.format(Locale.US, "%.5f, %.5f", point.lat, point.lon))
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                fieldForms.save(
                    point,
                    site.text?.toString().orEmpty(),
                    weather.text?.toString().orEmpty(),
                    conditions.text?.toString().orEmpty(),
                    notes.text?.toString().orEmpty()
                )
                toast("Field form saved")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMoreExports() {
        if (results.isEmpty()) {
            toast("Nothing to export.")
            return
        }
        val labels = arrayOf(
            "Shapefile package (.zip)",
            "Google Earth KMZ",
            "Current map image (PNG)",
            "Portable analysis QR",
            "Complete GIS bundle (.zip)",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("More exports")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> buildBinaryExport("Viewshed-shapefile.zip", "application/zip") {
                        AdvancedExport.toShapefileZip(results)
                    }
                    1 -> buildBinaryExport("Viewshed.kmz", "application/vnd.google-earth.kmz") {
                        AdvancedExport.toKmz(results)
                    }
                    2 -> exportMapSnapshot()
                    3 -> buildBinaryExport("Viewshed-QR.png", "image/png") {
                        ByteArrayOutputStream().use { output ->
                            QrExport.bitmap(QrExport.payload(results.first())).compress(Bitmap.CompressFormat.PNG, 100, output)
                            output.toByteArray()
                        }
                    }
                    4 -> buildBinaryExport("Viewshed-analysis.zip", "application/zip") {
                        AdvancedExport.toAnalysisBundle(results, binding.switchMultiObserver.isChecked)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildBinaryExport(title: String, mime: String, builder: () -> ByteArray) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { builder() }
                shareBytes(bytes, mime, title)
            } catch (error: Exception) {
                Log.e(TAG, "Export failed", error)
                toast("Export failed: ${error.message}")
            }
        }
    }

    private fun exportMapSnapshot() {
        if (!::map.isInitialized) return
        map.snapshot { bitmap ->
            if (bitmap == null) {
                toast("Map snapshot unavailable.")
                return@snapshot
            }
            lifecycleScope.launch(Dispatchers.Default) {
                val bytes = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
                withContext(Dispatchers.Main) { shareBytes(bytes, "image/png", "Viewshed-map.png") }
            }
        }
    }

    private fun shareBytes(bytes: ByteArray, mime: String, title: String) {
        val directory = File(cacheDir, "shared").apply { mkdirs() }
        val safeName = title.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val file = File(directory, safeName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share $title"))
    }

    private fun shareText(body: String, mime: String, title: String) {
        if (results.isEmpty()) {
            toast("Nothing to export.")
            return
        }
        Log.d(TAG, body.take(500))
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(share, "Share $title"))
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // --- Phase 1: Offline elevation packs ---

    private fun refreshOfflineStatus() {
        if (!::binding.isInitialized || !::offlineCache.isInitialized) return
        val packs = offlineCache.listPacks()
        binding.tvOfflineStatus.text = if (packs.isEmpty()) {
            getString(R.string.offline_status_empty)
        } else {
            getString(
                R.string.offline_status_format,
                OfflineMapCache.packSummary(packs)
            )
        }
    }

    private fun activeFieldPoint(): GeoPoint {
        observers.lastOrNull()?.let { return it }
        if (::map.isInitialized) {
            val c = map.cameraPosition.target
            return GeoPoint(c.latitude, c.longitude)
        }
        return GeoPoint(NEWBURGH.latitude, NEWBURGH.longitude)
    }

    private fun cacheOfflinePack() {
        val center = activeFieldPoint()
        val radiusKm = binding.etMaxDistance.text?.toString()?.toDoubleOrNull()?.coerceIn(0.5, 15.0)
            ?: 3.0
        val useDemoTerrain = binding.switchDemoTerrain.isChecked
        val elevationSource = selectedElevSource()
        binding.btnCacheOffline.isEnabled = false
        lifecycleScope.launch {
            try {
                toast("Caching elevations (~${radiusKm} km)…")
                val pack = withContext(Dispatchers.IO) {
                    offlineCache.captureArea(
                        name = "Area ${String.format(Locale.US, "%.3f,%.3f", center.lat, center.lon)}",
                        center = center,
                        radiusKm = radiusKm,
                        gridSteps = 40,
                    ) { points ->
                        elevationRepo.resolveElevations(
                            points = points,
                            useDemo = useDemoTerrain,
                            offline = null,
                            offlineOnly = false,
                            source = elevationSource,
                        ).let { grid ->
                            points.associate { p -> p.key() to grid.elevation(p) }
                        }
                    }
                }
                refreshOfflineStatus()
                toast("Offline pack saved: ${pack.sampleCount} samples · ${pack.radiusKm} km")
            } catch (e: Exception) {
                Log.e(TAG, "cacheOffline", e)
                toast("Cache failed: ${e.message}")
            } finally {
                binding.btnCacheOffline.isEnabled = true
            }
        }
    }

    private fun showManageOfflineDialog() {
        val packs = offlineCache.listPacks()
        if (packs.isEmpty()) {
            toast("No offline packs yet.")
            return
        }
        val labels = packs.map {
            "${it.name} · ${it.sampleCount} pts · r=${String.format(Locale.US, "%.1f", it.radiusKm)} km"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manage_offline)
            .setItems(labels) { _, which ->
                val pack = packs[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(pack.name)
                    .setMessage(
                        "Center ${String.format(Locale.US, "%.5f, %.5f", pack.centerLat, pack.centerLon)}\n" +
                            "${pack.sampleCount} samples · radius ${pack.radiusKm} km"
                    )
                    .setPositiveButton(R.string.delete) { _, _ ->
                        offlineCache.deletePack(pack.id)
                        if (offlineCache.listPacks().isEmpty()) {
                            binding.switchOfflineOnly.isChecked = false
                        }
                        refreshOfflineStatus()
                        toast("Pack deleted")
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Phase 2: Field notes ---

    private fun promptAddNote() {
        val point = activeFieldPoint()
        val input = EditText(this).apply {
            hint = getString(R.string.note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.note_title)
            .setMessage(
                String.format(Locale.US, "%.5f, %.5f", point.lat, point.lon)
            )
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text?.toString().orEmpty()
                if (text.isBlank()) {
                    toast("Note is empty.")
                    return@setPositiveButton
                }
                notesManager.add(point, text)
                refreshFieldMarkers()
                toast("Note saved")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNotesList() {
        val notes = notesManager.list()
        if (notes.isEmpty()) {
            toast("No field notes yet.")
            return
        }
        val labels = notes.map {
            val preview = it.text.take(48).let { t -> if (it.text.length > 48) "$t…" else t }
            preview
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.list_field_notes)
            .setItems(labels) { _, which ->
                showNoteDetail(notes[which].id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNoteDetail(id: String) {
        val note = notesManager.get(id) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.note_title)
            .setMessage(
                "${note.text}\n\n" +
                    String.format(Locale.US, "%.5f, %.5f", note.lat, note.lon)
            )
            .setPositiveButton("Go") { _, _ ->
                if (::map.isInitialized) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(note.lat, note.lon), 16f)
                    )
                }
            }
            .setNeutralButton(R.string.delete) { _, _ ->
                notesManager.remove(id)
                refreshFieldMarkers()
                toast("Note deleted")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshFieldMarkers() {
        if (!::map.isInitialized) return
        noteMarkers.forEach { it.remove() }
        noteMarkers.clear()
        memoMarkers.forEach { it.remove() }
        memoMarkers.clear()

        notesManager.list().forEach { note ->
            val m = map.addMarker(
                MarkerOptions()
                    .position(LatLng(note.lat, note.lon))
                    .title("Note")
                    .snippet(note.text.take(60))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
            )
            m?.tag = "note:${note.id}"
            m?.let { noteMarkers.add(it) }
        }
        voiceMemos.list().forEach { memo ->
            val m = map.addMarker(
                MarkerOptions()
                    .position(LatLng(memo.lat, memo.lon))
                    .title("Voice memo")
                    .snippet("Tap to play")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA))
            )
            m?.tag = "memo:${memo.id}"
            m?.let { memoMarkers.add(it) }
        }
    }

    // --- Phase 3: Voice memos ---

    private fun toggleVoiceMemo() {
        if (voiceMemos.isRecording) {
            val memo = voiceMemos.stopRecording()
            binding.btnVoiceMemo.text = getString(R.string.voice_memo_start)
            if (memo != null) {
                refreshFieldMarkers()
                toast("Voice memo saved")
            } else {
                toast("Recording failed or too short")
            }
        } else {
            val point = activeFieldPoint()
            val path = voiceMemos.startRecording(point)
            if (path == null) {
                toast("Could not start recorder")
            } else {
                binding.btnVoiceMemo.text = getString(R.string.voice_memo_stop)
                toast("Recording… tap again to stop")
            }
        }
    }

    private fun showMemosList() {
        val memos = voiceMemos.list()
        if (memos.isEmpty()) {
            toast("No voice memos yet.")
            return
        }
        val labels = memos.mapIndexed { i, m ->
            "#${i + 1} · ${String.format(Locale.US, "%.4f,%.4f", m.lat, m.lon)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.list_voice_memos)
            .setItems(labels) { _, which ->
                val memo = memos[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle("Voice memo")
                    .setMessage(
                        String.format(Locale.US, "%.5f, %.5f", memo.lat, memo.lon)
                    )
                    .setPositiveButton(R.string.play) { _, _ ->
                        if (!voiceMemos.play(memo)) toast("Playback failed")
                    }
                    .setNeutralButton(R.string.delete) { _, _ ->
                        voiceMemos.delete(memo.id)
                        refreshFieldMarkers()
                        toast("Memo deleted")
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun playMemoById(id: String) {
        val memo = voiceMemos.list().find { it.id == id } ?: return
        if (!voiceMemos.play(memo)) toast("Playback failed")
        else toast("Playing memo…")
    }

    // --- Measure mode (distance) ---

    private fun handleMeasureTap(point: GeoPoint) {
        val a = measureA
        if (a == null) {
            clearMeasurementOverlays()
            measureA = point
            val marker = if (::map.isInitialized) {
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(point.lat, point.lon))
                        .title("A")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
                )
            } else null
            marker?.let { measureMarkers.add(it) }
            binding.tvMeasureResult.text = "Point A set — tap point B"
        } else {
            val dist = MeasurementTool.calculateDistanceM(a, point)
            binding.tvMeasureResult.visibility = View.VISIBLE
            binding.tvMeasureResult.text = getString(
                R.string.measure_result_format,
                MeasurementTool.formatDistance(dist)
            )
            if (::map.isInitialized) {
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(point.lat, point.lon))
                        .title("B · ${MeasurementTool.formatDistance(dist)}")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
                )?.let { measureMarkers.add(it) }
                measureLine =
                    map.addPolyline(
                        PolylineOptions()
                            .add(LatLng(a.lat, a.lon), LatLng(point.lat, point.lon))
                            .color(0xFF00BCD4.toInt())
                            .width(6f)
                            .geodesic(true),
                    )
            }
            toast(MeasurementTool.formatDistance(dist))
            measureA = null
        }
    }

    private fun clearMeasurementOverlays() {
        measureA = null
        measureMarkers.forEach { it.remove() }
        measureMarkers.clear()
        measureLine?.remove()
        measureLine = null
    }

    companion object {
        private const val TAG = "Viewshed"
        private const val HEIGHT_STEP_M = 0.5
        private const val HEIGHT_MAX_M = 100.0
        private val NEWBURGH = LatLng(41.499, -74.010)

        // Grays only for multi-observer markers (no green/red brand)
        private val MARKER_HUES = floatArrayOf(
            BitmapDescriptorFactory.HUE_AZURE,
            BitmapDescriptorFactory.HUE_VIOLET,
            BitmapDescriptorFactory.HUE_ORANGE,
            BitmapDescriptorFactory.HUE_ROSE,
            BitmapDescriptorFactory.HUE_CYAN
        )

        // ARGB translucent grays
        private val FILL_COLORS = intArrayOf(
            0x66BDBDBD,
            0x669E9E9E,
            0x66757575,
            0x66E0E0E0,
            0x66616161
        )
        private val STROKE_COLORS = intArrayOf(
            0xFFE0E0E0.toInt(),
            0xFFBDBDBD.toInt(),
            0xFF9E9E9E.toInt(),
            0xFFEEEEEE.toInt(),
            0xFF757575.toInt()
        )
    }
}
