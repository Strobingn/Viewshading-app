package com.viewshed.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.viewshed.app.databinding.ActivityArTerrainBinding
import com.viewshed.app.viewshed.GeoPoint
import com.viewshed.app.viewshed.terrain.TerrainWorkspace
import java.util.Locale

class ArTerrainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var binding: ActivityArTerrainBinding
    private lateinit var sensors: SensorManager
    private lateinit var locationClient: FusedLocationProviderClient
    private val orientation = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private var lastLocation: Location? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasCameraPermission()) startCamera() else toast("Camera permission is required for terrain AR.")
        if (hasLocationPermission()) startLocation()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                lastLocation = location
                binding.arOverlay.updateLocation(
                    GeoPoint(location.latitude, location.longitude),
                    location.altitude.takeIf { location.hasAltitude() },
                )
                updateStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applySaved(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityArTerrainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.arRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        binding.arToolbar.setNavigationOnClickListener { finish() }
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        binding.arOverlay.setTerrain(TerrainWorkspace.current, TerrainWorkspace.candidates)
        if (TerrainWorkspace.current == null) {
            binding.tvArStatus.text = "Open Terrain Lab and load a georeferenced raster first. Compass overlay remains available."
        }
        permissions.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    override fun onStart() {
        super.onStart()
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        if (hasCameraPermission()) startCamera()
        if (hasLocationPermission()) startLocation()
    }

    override fun onStop() {
        sensors.unregisterListener(this)
        locationClient.removeLocationUpdates(locationCallback)
        super.onStop()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.arPreview.surfaceProvider
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (error: Exception) {
                toast("Camera start failed: ${error.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L).build()
        locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val remapped = FloatArray(9)
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val axes = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axes.first, axes.second, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val heading = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
        val pitch = Math.toDegrees(orientation[1].toDouble())
        val roll = Math.toDegrees(orientation[2].toDouble())
        binding.arOverlay.updateOrientation(heading, pitch, roll)
        binding.tvArStatus.tag = heading
        updateStatus()
    }

    private fun updateStatus() {
        val heading = (binding.tvArStatus.tag as? Double) ?: 0.0
        val location = lastLocation
        val count = TerrainWorkspace.candidates.size
        binding.tvArStatus.text = if (location == null) {
            String.format(Locale.US, "Heading %.0f° · waiting for GPS · %d terrain leads", heading, count)
        } else {
            String.format(Locale.US, "Heading %.0f° · GPS ±%.1f m · %d terrain leads", heading, location.accuracy, count)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
