package com.viewshed.app.viewshed

import android.util.Log

/**
 * Optional native Vulkan path (experimental).
 * Never crashes the app if the library is missing or NDK build is disabled.
 */
object VulkanViewshed {
    private const val TAG = "VulkanViewshed"

    @Volatile
    private var available: Boolean? = null

    fun isAvailable(): Boolean {
        available?.let { return it }
        return try {
            System.loadLibrary("viewshed_vulkan")
            available = true
            true
        } catch (t: Throwable) {
            Log.i(TAG, "Native Vulkan lib not loaded (using CPU engine): ${t.message}")
            available = false
            false
        }
    }

    /**
     * Attempts native compute; returns null so callers fall back to [ViewshedEngine].
     * Full GPU ray marching is still experimental — this keeps the bridge safe.
     */
    fun tryCompute(
        observer: GeoPoint,
        eyeHeightM: Double,
        maxDistM: Double,
        numRays: Int,
        samplesPerRay: Int
    ): FloatArray? {
        if (!isAvailable()) return null
        return try {
            nativeVulkanCompute(
                observer.lat.toFloat(),
                observer.lon.toFloat(),
                eyeHeightM.toFloat(),
                numRays,
                samplesPerRay,
                maxDistM.toFloat()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "nativeVulkanCompute failed", t)
            null
        }
    }

    @JvmStatic
    private external fun nativeVulkanCompute(
        observerLat: Float,
        observerLon: Float,
        observerHeight: Float,
        numRays: Int,
        samplesPerRay: Int,
        maxDist: Float
    ): FloatArray
}
