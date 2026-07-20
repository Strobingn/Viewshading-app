package com.viewshed.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viewshed.app.viewshed.HillshadeProcessor
import com.viewshed.app.viewshed.LidarClassifier
import com.viewshed.app.viewshed.LidarPointCloudRenderer

@Composable
fun HillshadeControls(
    groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
    onHillshadeUpdated: (android.graphics.Bitmap) -> Unit
) {
    var azimuth by remember { mutableStateOf(315f) }
    var altitude by remember { mutableStateOf(35f) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Azimuth (Sun Direction): ${azimuth.toInt()}°")
        Slider(
            value = azimuth,
            onValueChange = { newValue ->
                azimuth = newValue
                updateHillshade(groundPoints, azimuth, altitude, onHillshadeUpdated)
            },
            valueRange = 0f..360f
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text("Altitude (Sun Height): ${altitude.toInt()}°")
        Slider(
            value = altitude,
            onValueChange = { newValue ->
                altitude = newValue
                updateHillshade(groundPoints, azimuth, altitude, onHillshadeUpdated)
            },
            valueRange = 5f..85f
        )
    }
}

private fun updateHillshade(
    groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
    azimuth: Float,
    altitude: Float,
    onUpdated: (android.graphics.Bitmap) -> Unit
) {
    val bitmap = HillshadeProcessor.generateHillshadeFromGroundPoints(
        groundPoints = groundPoints,
        cellSize = 0.5,
        azimuth = azimuth.toDouble(),
        altitude = altitude.toDouble()
    )
    bitmap?.let { onUpdated(it) }
}
