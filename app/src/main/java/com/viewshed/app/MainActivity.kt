package com.viewshed.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.viewshed.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Viewshed Calculator — Google Maps + radial LOS analysis.
 *
 * Long-press map → place observer → Calculate Viewshed.
 * Demo terrain works offline; real mode uses Google Elevation API (batched).
 */
class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var observerMarker: Marker? = null
    private var visiblePolygon: Polygon? = null
    private var observerLatLng: LatLng? = null
    private var lastBoundary: List<LatLng> = emptyList()

    private val elevationService: ElevationService by lazy {
        Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/maps/api/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            )
            .build()
            .create(ElevationService::class.java)
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            enableMyLocation()
        } else {
            Toast.makeText(
                this,
                "Location denied — using Newburgh NY default.",
                Toast.LENGTH_LONG
            ).show()
            moveToDefaultLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupUI()
    }

    private fun setupUI() {
        binding.btnCalculate.setOnClickListener {
            val latLng = observerLatLng
            if (latLng == null) {
                Toast.makeText(this, "Long press on map to place observer first!", Toast.LENGTH_SHORT)
                    .show()
            } else {
                calculateViewshed(latLng)
            }
        }

        binding.btnClear.setOnClickListener { clearOverlays() }
        binding.btnExport.setOnClickListener { exportGeoJson() }

        binding.etObserverHeight.setText("1.7")
        binding.etMaxDistance.setText("5.0")
        binding.etNumRays.setText("72")
        binding.etSampleSteps.setText("80")
        binding.etRefraction.setText("0.13")
        binding.switchDemoTerrain.isChecked = true
        binding.switchCurvature.isChecked = true
        binding.btnExport.isEnabled = false
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.mapType = GoogleMap.MAP_TYPE_HYBRID
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true

        map.setOnMapLongClickListener { placeObserver(it) }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocation()
        } else {
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        moveToDefaultLocation()
    }

    private fun moveToDefaultLocation() {
        if (!::map.isInitialized) return
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(NEWBURGH, 12f))
    }

    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        map.isMyLocationEnabled = true
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 13f)
                )
            }
        }
    }

    private fun placeObserver(latLng: LatLng) {
        observerMarker?.remove()
        observerMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Observer")
                .snippet("Eye height: ${binding.etObserverHeight.text} m")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )
        observerLatLng = latLng
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14f))
        Toast.makeText(this, "Observer placed. Tap Calculate Viewshed.", Toast.LENGTH_SHORT).show()
    }

    private fun calculateViewshed(observer: LatLng) {
        lifecycleScope.launch {
            try {
                binding.btnCalculate.isEnabled = false
                binding.btnCalculate.text = "Calculating..."
                binding.progressBar.visibility = View.VISIBLE

                val height = binding.etObserverHeight.text.toString().toDoubleOrNull() ?: 1.7
                val maxDistKm = binding.etMaxDistance.text.toString().toDoubleOrNull() ?: 5.0
                val numRays = (binding.etNumRays.text.toString().toIntOrNull() ?: 72).coerceIn(8, 360)
                val samples = (binding.etSampleSteps.text.toString().toIntOrNull() ?: 80).coerceIn(10, 200)
                val useDemo = binding.switchDemoTerrain.isChecked
                val useCurvature = binding.switchCurvature.isChecked
                val refraction = binding.etRefraction.text.toString().toDoubleOrNull() ?: 0.13

                val boundary = withContext(Dispatchers.Default) {
                    computeViewshedBoundary(
                        observer = observer,
                        eyeHeightM = height,
                        maxDistKm = maxDistKm,
                        numRays = numRays,
                        samples = samples,
                        useDemo = useDemo,
                        useCurvature = useCurvature,
                        refraction = refraction
                    )
                }

                lastBoundary = boundary
                drawVisiblePolygon(boundary)
                binding.btnExport.isEnabled = boundary.size >= 3

                Toast.makeText(
                    this@MainActivity,
                    "Viewshed ready — ${boundary.size} boundary points.",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Calculation error", e)
                Toast.makeText(
                    this@MainActivity,
                    "Error: ${e.message}. Try demo mode or check API key.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnCalculate.isEnabled = true
                binding.btnCalculate.text = getString(R.string.calculate_viewshed)
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private suspend fun computeViewshedBoundary(
        observer: LatLng,
        eyeHeightM: Double,
        maxDistKm: Double,
        numRays: Int,
        samples: Int,
        useDemo: Boolean,
        useCurvature: Boolean,
        refraction: Double
    ): List<LatLng> {
        // Build all sample points (observer + rays)
        data class Sample(val ray: Int, val step: Int, val latLng: LatLng, val distM: Double)

        val samplesList = ArrayList<Sample>(numRays * samples + 1)
        samplesList.add(Sample(-1, 0, observer, 0.0))
        for (i in 0 until numRays) {
            val bearing = i * 360.0 / numRays
            for (s in 1..samples) {
                val distM = s * (maxDistKm * 1000.0) / samples
                samplesList.add(
                    Sample(i, s, computeDestinationPoint(observer, bearing, distM), distM)
                )
            }
        }

        val elevMap = if (useDemo) {
            samplesList.associate { s ->
                s.latLng to getDemoElevation(s.latLng.latitude, s.latLng.longitude)
            }
        } else {
            fetchElevationsBatched(samplesList.map { it.latLng })
        }

        val observerElev = elevMap[observer]
            ?: getDemoElevation(observer.latitude, observer.longitude)
        val eyeElev = observerElev + eyeHeightM

        val boundary = ArrayList<LatLng>(numRays + 1)
        for (i in 0 until numRays) {
            val bearing = i * 360.0 / numRays
            var maxVisibleDist = 0.0

            for (s in 1..samples) {
                val distM = s * (maxDistKm * 1000.0) / samples
                val target = computeDestinationPoint(observer, bearing, distM)
                val terrainElev = elevMap[target]
                    ?: getDemoElevation(target.latitude, target.longitude)
                val losHeight = computeLineOfSightHeight(eyeElev, distM, useCurvature, refraction)
                if (terrainElev > losHeight) break
                maxVisibleDist = distM
            }

            if (maxVisibleDist > 0) {
                boundary.add(computeDestinationPoint(observer, bearing, maxVisibleDist))
            } else {
                boundary.add(observer)
            }
        }

        if (boundary.isNotEmpty()) {
            boundary.add(boundary.first())
        }
        return boundary
    }

    /** Google Elevation allows up to 512 locations per request, pipe-separated. */
    private suspend fun fetchElevationsBatched(points: List<LatLng>): Map<LatLng, Double> {
        val result = HashMap<LatLng, Double>(points.size)
        val key = BuildConfig.MAPS_API_KEY
        if (key.isBlank()) {
            Log.w(TAG, "No MAPS_API_KEY — falling back to demo elevations")
            points.forEach { result[it] = getDemoElevation(it.latitude, it.longitude) }
            return result
        }

        val unique = points.distinctBy { "${"%.6f".format(it.latitude)},${"%.6f".format(it.longitude)}" }
        for (chunk in unique.chunked(ELEVATION_BATCH_SIZE)) {
            val locations = chunk.joinToString("|") { "${it.latitude},${it.longitude}" }
            try {
                val response = withContext(Dispatchers.IO) {
                    elevationService.getElevation(locations, key)
                }
                if (response.status == "OK" && response.results.isNotEmpty()) {
                    response.results.forEachIndexed { idx, elev ->
                        if (idx < chunk.size) {
                            result[chunk[idx]] = elev.elevation
                        }
                    }
                } else {
                    Log.w(TAG, "Elevation API: ${response.status}")
                    chunk.forEach { result[it] = getDemoElevation(it.latitude, it.longitude) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Elevation batch failed", e)
                chunk.forEach { result[it] = getDemoElevation(it.latitude, it.longitude) }
            }
        }

        // Fill any missing
        points.forEach { p ->
            if (p !in result) {
                result[p] = getDemoElevation(p.latitude, p.longitude)
            }
        }
        return result
    }

    private fun computeDestinationPoint(start: LatLng, bearingDeg: Double, distanceM: Double): LatLng {
        val r = EARTH_RADIUS_M
        val d = distanceM / r
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(d) * cos(lat1),
            cos(d) - sin(lat1) * sin(lat2)
        )
        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun computeLineOfSightHeight(
        observerElev: Double,
        distM: Double,
        useCurvature: Boolean,
        refractionCoeff: Double
    ): Double {
        if (!useCurvature) return observerElev
        val curvatureDrop = (distM * distM) / (2 * EARTH_RADIUS_M)
        val refractionCorrection = refractionCoeff * curvatureDrop
        return observerElev - curvatureDrop + refractionCorrection
    }

    /** Deterministic demo hills around Newburgh / Hudson valley. */
    private fun getDemoElevation(lat: Double, lon: Double): Double {
        val base = 20.0
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val hill1 = 80 * sin(latRad * 50) * cos(lonRad * 40)
        val hill2 = 40 * sin(latRad * 120 + 1.3) * cos(lonRad * 90)
        val hill3 = 25 * sin(latRad * 300) * cos(lonRad * 250)
        val riverEffect = if (lon < -73.95) -15.0 else 0.0
        val noise = coordHash(lat, lon) * 5
        return base + hill1 + hill2 + hill3 + riverEffect + noise
    }

    private fun coordHash(lat: Double, lon: Double): Double {
        val h = (lat * 100_000 + lon * 100_000).toLong()
        return (h % 100) / 100.0
    }

    private fun drawVisiblePolygon(boundary: List<LatLng>) {
        visiblePolygon?.remove()
        if (boundary.size < 3) {
            Toast.makeText(this, "Not enough visible points for polygon.", Toast.LENGTH_SHORT).show()
            return
        }
        visiblePolygon = map.addPolygon(
            PolygonOptions()
                .addAll(boundary)
                .fillColor(0x6600C853)
                .strokeColor(0xFF00A040.toInt())
                .strokeWidth(3f)
                .geodesic(true)
        )
        observerMarker?.snippet = "Viewshed calculated — green = visible"
    }

    private fun clearOverlays() {
        visiblePolygon?.remove()
        visiblePolygon = null
        observerMarker?.remove()
        observerMarker = null
        observerLatLng = null
        lastBoundary = emptyList()
        binding.btnExport.isEnabled = false
        Toast.makeText(this, "Cleared. Long press to place new observer.", Toast.LENGTH_SHORT).show()
    }

    private fun exportGeoJson() {
        val points = lastBoundary
        if (points.size < 3) {
            Toast.makeText(this, "No visible polygon to export.", Toast.LENGTH_SHORT).show()
            return
        }

        val coords = points.joinToString(",") { "[${it.longitude},${it.latitude}]" }
        val obsLat = observerLatLng?.latitude
        val obsLon = observerLatLng?.longitude
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "geometry": {
                  "type": "Polygon",
                  "coordinates": [[$coords]]
                },
                "properties": {
                  "observer_lat": $obsLat,
                  "observer_lon": $obsLon,
                  "description": "Viewshed visible area"
                }
              }]
            }
        """.trimIndent()

        Log.d(TAG, geoJson)

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/geo+json"
            putExtra(Intent.EXTRA_SUBJECT, "Viewshed GeoJSON")
            putExtra(Intent.EXTRA_TEXT, geoJson)
        }
        startActivity(Intent.createChooser(share, "Share Viewshed GeoJSON"))
    }

    interface ElevationService {
        @GET("elevation/json")
        suspend fun getElevation(
            @Query("locations") locations: String,
            @Query("key") key: String
        ): ElevationResponse
    }

    data class ElevationResponse(
        val status: String,
        val results: List<ElevationResult> = emptyList()
    )

    data class ElevationResult(
        val elevation: Double,
        val resolution: Double? = null
    )

    companion object {
        private const val TAG = "Viewshed"
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val ELEVATION_BATCH_SIZE = 100
        private val NEWBURGH = LatLng(41.499, -74.010)
    }
}
