package com.viewshed.app.ui

import androidx.compose.runtime.*
import com.viewshed.app.viewshed.*

@Composable
fun ArDisturbanceControls(
    groundPoints: List<LidarPointCloudRenderer.LidarPoint>,
    onDisturbancesDetected: (List<GroundDisturbanceDetector.Disturbance>) -> Unit
) {
    var showAr by remember { mutableStateOf(false) }

    // Button to run detection + launch AR overlay
    // When clicked: run GroundDisturbanceDetector + show AR view with highlights
}
