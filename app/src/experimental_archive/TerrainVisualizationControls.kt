package com.viewshed.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.viewshed.app.viewshed.*

@Composable
fun TerrainVisualizationControls(
    groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
    onVisualizationChanged: (android.graphics.Bitmap) -> Unit
) {
    var mode by remember { mutableStateOf("Hillshade") }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Terrain Visualization Mode")

        Row {
            Button(onClick = { mode = "Hillshade"; /* update */ }) { Text("Hillshade") }
            Button(onClick = { mode = "SkyViewFactor"; /* update */ }) { Text("Sky View Factor") }
            Button(onClick = { mode = "Openness"; /* update */ }) { Text("Openness") }
        }

        // Sliders from HillshadeControls can be reused here for lighting
    }
}
