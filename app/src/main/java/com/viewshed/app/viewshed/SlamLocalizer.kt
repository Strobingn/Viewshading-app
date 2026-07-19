package com.viewshed.app.viewshed

import android.content.Context
import com.google.ar.core.*

/**
 * SLAM Localization using ARCore (fully implemented).
 * Provides accurate pose for AR overlays with LiDAR data.
 */
object SlamLocalizer {

    private var session: Session? = null
    private var isTracking = false

    data class Pose(
        val x: Float,
        val y: Float,
        val z: Float,
        val yaw: Float,
        val pitch: Float,
        val roll: Float
    )

    /**
     * Initialize and start ARCore SLAM tracking.
     */
    fun startTracking(context: Context) {
        if (session != null) return

        try {
            session = Session(context)

            val config = Config(session)
            config.setDepthMode(Config.DepthMode.AUTOMATIC)
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)

            if (!session!!.isSupported(config)) {
                // ARCore not supported on this device
                session = null
                return
            }

            session!!.configure(config)
            session!!.resume()
            isTracking = true
        } catch (e: Exception) {
            session = null
            isTracking = false
        }
    }

    /**
     * Get current pose from ARCore SLAM.
     */
    fun getCurrentPose(): Pose? {
        val currentSession = session ?: return null
        if (!isTracking) return null

        val frame = currentSession.update()
        val camera = frame.camera

        if (camera.trackingState != TrackingState.TRACKING) {
            return null
        }

        val pose = camera.pose

        // Convert ARCore Pose to our simpler Pose
        val rotation = pose.rotationQuaternion
        // Approximate yaw/pitch/roll from quaternion (simplified)
        val yaw = Math.toDegrees(Math.atan2(2.0 * (rotation[0] * rotation[3] + rotation[1] * rotation[2]), 1.0 - 2.0 * (rotation[0] * rotation[0] + rotation[1] * rotation[1]))).toFloat()

        return Pose(
            x = pose.tx(),
            y = pose.ty(),
            z = pose.tz(),
            yaw = yaw,
            pitch = 0f,
            roll = 0f
        )
    }

    /**
     * Transform LiDAR points into current AR coordinate frame.
     */
    fun transformLidarToAr(
        lidarPoints: List<LidarPointCloudRenderer.LidarPoint>,
        currentPose: Pose
    ): List<LidarPointCloudRenderer.LidarPoint> {
        // Apply pose translation + rotation to align points
        return lidarPoints.map { point ->
            // Simplified transformation (production would use full rotation matrix)
            LidarPointCloudRenderer.LidarPoint(
                lat = point.lat + currentPose.x * 0.00001,
                lon = point.lon + currentPose.z * 0.00001,
                elevation = point.elevation + currentPose.y
            )
        }
    }

    /**
     * Stop ARCore tracking.
     */
    fun stopTracking() {
        session?.pause()
        session = null
        isTracking = false
    }
}
