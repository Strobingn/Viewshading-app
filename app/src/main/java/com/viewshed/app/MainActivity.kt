package com.viewshed.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.viewshed.app.databinding.ActivityMainBinding
import com.viewshed.app.viewshed.AnalysisPreset
import com.viewshed.app.viewshed.AnalysisSession
import com.viewshed.app.viewshed.AnalysisSessionManager
import com.viewshed.app.viewshed.ElevationDataException
import com.viewshed.app.viewshed.ElevationRepository
import com.viewshed.app.viewshed.GeoExport
import com.viewshed.app.viewshed.GeoPoint
import com.viewshed.app.viewshed.SampleQuality
import com.viewshed.app.viewshed.ViewshedEngine
import com.viewshed.app.viewshed.ViewshedParams
import com.viewshed.app.viewshed.ViewshedResult
import com.viewshed.app.viewshed.VulkanViewshed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val observers = mutableListOf<GeoPoint>()
    private val markers = mutableListOf<Marker>()
    private val polygons = mutableListOf<Polygon>()
    private val results = mutableListOf<ViewshedResult>()

    private var calcJob: Job? = null
    /** Prevents slider ↔ text feedback loops while editing viewer height. */
    private var syncingHeight = false

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            if (::map.isInitialized) enableMyLocation(centerIfAvailable = true)
        } else {
            toast("Location denied — Newburgh NY default.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applySaved(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            binding.bottomSheet.updatePadding(bottom = bars.bottom)
            val topLp = binding.topBar.layoutParams as ViewGroup.MarginLayoutParams
            topLp.topMargin = bars.top + (12 * resources.displayMetrics.density).toInt()
            binding.topBar.layoutParams = topLp
            insets
        }

        binding.bottomSheet.post { updatePeekToActionButtons() }
        binding.actionButtons.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePeekToActionButtons()
        }
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) = Unit
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                positionFabs(bottomSheetBehavior.peekHeight)
            }
        })
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
        }
    }

    private fun positionFabs(peekPx: Int) {
        val lp = binding.fabColumn.layoutParams as ViewGroup.MarginLayoutParams
        val gap = (12 * resources.displayMetrics.density).toInt()
        lp.bottomMargin = peekPx + gap
        binding.fabColumn.layoutParams = lp
    }

    private fun setupUi() {
        binding.btnCalculate.setOnClickListener { runCalculation() }
        binding.btnClear.setOnClickListener { clearAll() }
        binding.btnExportGeoJson.setOnClickListener { shareText(GeoExport.toGeoJson(results, multiObserver = binding.switchMultiObserver.isChecked), "application/geo+json", "Viewshed.geojson") }
        binding.btnExportKml.setOnClickListener { shareText(GeoExport.toKml(results), "application/vnd.google-earth.kml+xml", "Viewshed.kml") }

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

        // Prefer real elevation when Maps key is baked in
        binding.switchDemoTerrain.isChecked = !BuildConfig.HAS_MAPS_API_KEY
        if (!BuildConfig.HAS_MAPS_API_KEY) {
            Log.w(TAG, getString(R.string.no_api_key_demo))
        }
        // Probe optional native path once (never required)
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = VulkanViewshed.isAvailable()
            Log.i(TAG, "Vulkan experimental available=$ok")
        }

        setExportEnabled(false)
    }

    private fun setupQualityAndExperimental() {
        binding.chipQualityLow.setOnClickListener { applyQuality(SampleQuality.LOW) }
        binding.chipQualityMed.setOnClickListener { applyQuality(SampleQuality.MEDIUM) }
        binding.chipQualityHigh.setOnClickListener { applyQuality(SampleQuality.HIGH) }

        // These modes previously sampled missing elevations and assumed visibility
        // became permanently hidden after the first obstruction.
        binding.switchAdaptive.isChecked = false
        binding.switchAdaptive.isEnabled = false
        binding.switchBinaryHorizon.isChecked = false
        binding.switchBinaryHorizon.isEnabled = false
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
                // Night mode change recreates activities when needed
                recreate()
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
            // Slider is in centimetres (0–1000)
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

        map.setOnMapLongClickListener { latLng ->
            placeObserver(
                GeoPoint(latLng.latitude, latLng.longitude),
                clearOthers = !binding.switchMultiObserver.isChecked
            )
        }

        // Default camera first; GPS may override — never overwrite GPS with Newburgh later.
        moveToDefaultLocation()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
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

    private fun enableMyLocation(centerIfAvailable: Boolean = true) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
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

    private fun placeObserverAtMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
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
        }
    }

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

    private fun readParams(): ViewshedParams = ViewshedParams(
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
        quality = selectedQuality()
    ).sanitized()

    private fun runCalculation() {
        if (observers.isEmpty()) {
            toast("Long-press the map to place an observer first.")
            return
        }
        if (!::map.isInitialized) {
            toast("Map not ready yet.")
            return
        }
        calcJob?.cancel()
        calcJob = lifecycleScope.launch {
            try {
                setCalculating(true)
                val params = readParams()
                val multi = binding.switchMultiObserver.isChecked
                val targets = if (multi) observers.toList() else listOf(observers.last())

                // Always redraw this run's polygons (avoid stacking duplicates)
                polygons.forEach { it.remove() }
                polygons.clear()
                results.clear()

                val newResults = mutableListOf<ViewshedResult>()
                targets.forEachIndexed { index, observer ->
                    binding.tvProgress.visibility = View.VISIBLE
                    binding.tvProgress.text = "Observer ${index + 1}/${targets.size}…"

                    val samples = ViewshedEngine.samplePoints(observer, params)
                    val elev = withContext(Dispatchers.IO) {
                        elevationRepo.resolveElevations(samples, params.useDemoTerrain)
                    }
                    val result = ViewshedEngine.compute(
                        observer = observer,
                        params = params,
                        elevations = elev,
                        onRayProgress = { done, total ->
                            withContext(Dispatchers.Main.immediate) {
                                val pct = (100.0 * done / total).toInt()
                                binding.progressBar.progress = pct
                                binding.tvProgress.text = getString(
                                    R.string.progress_format,
                                    done,
                                    total,
                                    pct
                                )
                            }
                        }
                    )
                    newResults.add(result)
                    if (::map.isInitialized) drawResult(result, index)
                }

                results.addAll(newResults)
                showStats(results)
                setExportEnabled(results.any { it.visibleSectors.isNotEmpty() })
                newResults.lastOrNull()?.let { last ->
                    try {
                        val file = java.io.File(filesDir, "last_session.json")
                        AnalysisSessionManager.save(AnalysisSession.fromResult(last), file)
                    } catch (e: Exception) {
                        Log.w(TAG, "session save failed", e)
                    }
                }
                val mode = if (params.useDemoTerrain) "demo" else "elevation API"
                toast("Viewshed ready — ${results.size} observer(s) · $mode")
            } catch (e: ElevationDataException) {
                Log.e(TAG, "Real elevation unavailable", e)
                toast(e.message ?: "Real elevation unavailable. No fake terrain was substituted.")
            } catch (e: Exception) {
                Log.e(TAG, "Calculation error", e)
                toast("Calculation failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setCalculating(false)
            }
        }
    }

    private fun drawResult(result: ViewshedResult, colorIndex: Int) {
        if (result.visibleSectors.isEmpty()) return
        try {
            val fill = FILL_COLORS[colorIndex % FILL_COLORS.size]
            val stroke = STROKE_COLORS[colorIndex % STROKE_COLORS.size]
            result.visibleSectors.forEach { sector ->
                val polygon = map.addPolygon(
                    PolygonOptions()
                        .addAll(sector.boundary.map { LatLng(it.lat, it.lon) })
                        .fillColor(fill)
                        .strokeColor(stroke)
                        .strokeWidth(0f)
                        .geodesic(true)
                )
                polygons.add(polygon)
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
        val format = if (list.size > 1) {
            R.string.stats_multi_format
        } else {
            R.string.stats_format
        }
        binding.tvStats.text = getString(format, maxKm, avgKm, area, rays)
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
        calcJob?.cancel()
        clearOverlaysOnly()
        observers.clear()
        binding.tvStats.visibility = View.GONE
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
