package com.viewshed.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.viewshed.app.databinding.ActivityFieldProjectBinding
import com.viewshed.app.viewshed.FieldCsvExport
import com.viewshed.app.viewshed.FieldGpxExport
import com.viewshed.app.viewshed.FieldProject
import com.viewshed.app.viewshed.FieldProjectStore
import com.viewshed.app.viewshed.FieldTrack
import com.viewshed.app.viewshed.FieldTrackPoint
import com.viewshed.app.viewshed.FieldWaypoint
import com.viewshed.app.viewshed.GeoPoint
import com.viewshed.app.viewshed.GpsQualityGate
import java.io.File
import java.util.Locale

class FieldProjectActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFieldProjectBinding
    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var store: FieldProjectStore
    private var project: FieldProject? = null
    private var lastLocation: Location? = null
    private var tracking = false
    private val activeTrack = mutableListOf<FieldTrackPoint>()

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            startLocationUpdates()
        } else toast("Location permission is required for field capture.")
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::handleLocation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applySaved(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityFieldProjectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.fieldProjectRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        store = FieldProjectStore(this)
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        binding.fieldProjectToolbar.setNavigationOnClickListener { finish() }
        binding.btnFieldNewProject.setOnClickListener { promptNewProject() }
        binding.btnFieldOpenProject.setOnClickListener { showProjects() }
        binding.btnFieldWaypoint.setOnClickListener { captureWaypoint() }
        binding.btnFieldTrack.setOnClickListener { toggleTrack() }
        binding.btnFieldExportGpx.setOnClickListener {
            project?.let { shareText(FieldGpxExport.export(it), "application/gpx+xml", "${safeName(it.name)}.gpx") }
        }
        binding.btnFieldExportCsv.setOnClickListener {
            project?.let { shareText(FieldCsvExport.waypoints(it), "text/csv", "${safeName(it.name)}-waypoints.csv") }
        }
        val savedId = getSharedPreferences("field_project", MODE_PRIVATE).getString("current_id", null)
        savedId?.let { store.load(it) }?.let(::selectProject)
    }

    override fun onStart() {
        super.onStart()
        if (hasLocationPermission()) startLocationUpdates() else permissionRequest.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    override fun onStop() {
        if (tracking) finishTrack()
        locationClient.removeLocationUpdates(callback)
        super.onStop()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMaxUpdateDelayMillis(4_000L)
            .build()
        locationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun handleLocation(location: Location) {
        lastLocation = location
        val age = (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        val assessment = GpsQualityGate.assess(location.accuracy, age)
        binding.tvFieldGps.text = String.format(
            Locale.US,
            "GPS %.6f, %.6f · ±%.1f m · %s",
            location.latitude,
            location.longitude,
            location.accuracy,
            assessment.grade.name.lowercase(Locale.US),
        )
        if (tracking && assessment.accepted) {
            val next = FieldTrackPoint(
                point = GeoPoint(location.latitude, location.longitude),
                altitudeM = location.altitude.takeIf { location.hasAltitude() },
                accuracyM = location.accuracy,
                timestampEpochMs = location.time,
            )
            if (activeTrack.lastOrNull()?.point?.let { com.viewshed.app.viewshed.GeoMath.distanceM(it, next.point) >= 1.0 } != false) {
                activeTrack += next
                updateStats()
            }
        }
    }

    private fun promptNewProject() {
        val input = EditText(this).apply { hint = "Project name"; setPadding(48, 24, 48, 8) }
        MaterialAlertDialogBuilder(this)
            .setTitle("New field project")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) {
                    val created = FieldProject(name = name)
                    store.save(created)
                    selectProject(created)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showProjects() {
        val projects = store.list()
        if (projects.isEmpty()) {
            toast("No saved field projects.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Open field project")
            .setItems(projects.map { "${it.name} · ${it.waypoints.size} points · ${it.tracks.size} tracks" }.toTypedArray()) { _, which ->
                selectProject(projects[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun selectProject(value: FieldProject) {
        project = value
        getSharedPreferences("field_project", MODE_PRIVATE).edit().putString("current_id", value.id).apply()
        binding.tvFieldProjectName.text = value.name
        updateStats()
    }

    private fun captureWaypoint() {
        val current = project ?: return
        val location = lastLocation ?: run { toast("Waiting for a GPS fix."); return }
        val age = (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        val assessment = GpsQualityGate.assess(location.accuracy, age)
        if (!assessment.accepted) {
            toast(assessment.message)
            return
        }
        val input = EditText(this).apply { hint = "Waypoint name / notes"; setPadding(48, 24, 48, 8) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Capture waypoint")
            .setMessage(String.format(Locale.US, "%.6f, %.6f · ±%.1f m", location.latitude, location.longitude, location.accuracy))
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val waypoint = FieldWaypoint(
                    name = input.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
                        ?: "Waypoint ${current.waypoints.size + 1}",
                    point = GeoPoint(location.latitude, location.longitude),
                    altitudeM = location.altitude.takeIf { location.hasAltitude() },
                    horizontalAccuracyM = location.accuracy,
                    capturedAtEpochMs = location.time,
                )
                val updated = current.copy(waypoints = current.waypoints + waypoint)
                store.save(updated)
                selectProject(updated)
                toast("Waypoint saved")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleTrack() {
        if (tracking) finishTrack() else {
            activeTrack.clear()
            tracking = true
            binding.btnFieldTrack.text = "Stop and save track"
            toast("Track recording started")
        }
    }

    private fun finishTrack() {
        tracking = false
        binding.btnFieldTrack.text = "Start GPS track"
        val current = project ?: return
        if (activeTrack.size < 2) {
            activeTrack.clear()
            toast("Track discarded: fewer than two accurate fixes")
            return
        }
        val track = FieldTrack(name = "Track ${current.tracks.size + 1}", points = activeTrack.toList())
        activeTrack.clear()
        val updated = current.copy(tracks = current.tracks + track)
        store.save(updated)
        selectProject(updated)
        toast(String.format(Locale.US, "Track saved · %.1f m", track.distanceM))
    }

    private fun updateStats() {
        val current = project
        val enabled = current != null
        binding.btnFieldWaypoint.isEnabled = enabled
        binding.btnFieldTrack.isEnabled = enabled
        binding.btnFieldExportGpx.isEnabled = enabled
        binding.btnFieldExportCsv.isEnabled = enabled
        binding.tvFieldProjectStats.text = if (current == null) {
            "Waypoints 0 · Tracks 0"
        } else {
            val distance = current.tracks.sumOf { it.distanceM } +
                if (tracking && activeTrack.size > 1) activeTrack.zipWithNext().sumOf { com.viewshed.app.viewshed.GeoMath.distanceM(it.first.point, it.second.point) } else 0.0
            String.format(Locale.US, "Waypoints %d · Tracks %d · distance %.1f m%s", current.waypoints.size, current.tracks.size, distance, if (tracking) " · recording ${activeTrack.size} fixes" else "")
        }
    }

    private fun shareText(body: String, mime: String, filename: String) {
        val directory = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(directory, filename).apply { writeText(body) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share $filename"))
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "-")
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
