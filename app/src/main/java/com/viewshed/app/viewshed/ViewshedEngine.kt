package com.viewshed.app.viewshed

import kotlinx.coroutines.*
import kotlin.math.*

object ViewshedEngine {

    init {
        System.loadLibrary("viewshed_vulkan")
    }

    private external fun nativeVulkanCompute(
        observerLat: Float,
        observerLon: Float,
        observerHeight: Float,
        numRays: Int,
        samplesPerRay: Int,
        maxDist: Float
    ): FloatArray

    // ... existing Quality, Result, computeViewshed, adaptiveSampleRay, linearSampleRay, binarySearchHorizon ...

    suspend fun vulkanComputeViewshed(
        observer: GeoPoint,
        eyeHeightM: Double = 1.5,
        maxDistanceM: Double = 5000.0,
        numRays: Int = 72
    ): ViewshedResult = withContext(Dispatchers.Default) {
        val terrainH = ElevationRepository.getElevation(observer)
        val resultArray = nativeVulkanCompute(
            observer.latitude.toFloat(),
            observer.longitude.toFloat(),
            (terrainH + eyeHeightM).toFloat(),
            numRays,
            40,
            maxDistanceM.toFloat()
        )

        // Parse resultArray into real visible points when native impl is complete
        // For now it returns basic data; replace parsing with real output
        val visible = listOf(GeoPoint(resultArray.getOrElse(0) { 0f }.toDouble(), resultArray.getOrElse(1) { 0f }.toDouble()))

        ViewshedResult(visible, observer, maxDistanceM, numRays, 40)
    }
}
