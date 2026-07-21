package com.viewshed.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.viewshed.app.viewshed.GeoMath
import com.viewshed.app.viewshed.GeoPoint
import com.viewshed.app.viewshed.terrain.TerrainFeatureCandidate
import com.viewshed.app.viewshed.terrain.TerrainRaster
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class ArTerrainOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var heading = 0.0
    private var pitch = 0.0
    private var roll = 0.0
    private var location: GeoPoint? = null
    private var altitudeM: Double? = null
    private var raster: TerrainRaster? = null
    private var candidates: List<TerrainFeatureCandidate> = emptyList()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 196, 0)
        strokeWidth = 3f
        textSize = 34f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        style = Paint.Style.FILL
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    fun updateOrientation(headingDeg: Double, pitchDeg: Double, rollDeg: Double) {
        heading = GeoMath.clampBearing(headingDeg)
        pitch = pitchDeg
        roll = rollDeg
        invalidate()
    }

    fun updateLocation(point: GeoPoint, altitudeM: Double?) {
        location = point
        this.altitudeM = altitudeM
        invalidate()
    }

    fun setTerrain(raster: TerrainRaster?, candidates: List<TerrainFeatureCandidate>) {
        this.raster = raster
        this.candidates = candidates
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val horizonY = (
            height / 2f + pitch.toFloat() / VERTICAL_FOV_DEG.toFloat() * height.toFloat()
        ).coerceIn(0f, height.toFloat())
        canvas.save()
        canvas.rotate(-roll.toFloat(), width / 2f, horizonY)
        canvas.drawLine(0f, horizonY, width.toFloat(), horizonY, gridPaint)
        for (offset in -30..30 step 10) {
            val x = width / 2f + offset.toFloat() / HORIZONTAL_FOV_DEG.toFloat() * width.toFloat()
            canvas.drawLine(x, horizonY - 14f, x, horizonY + 14f, gridPaint)
            canvas.drawText("${GeoMath.clampBearing(heading + offset).toInt()}°", x + 5f, horizonY - 18f, labelPaint)
        }
        canvas.restore()

        val here = location ?: return
        val terrain = raster ?: return
        candidates.take(100).forEach { feature ->
            val target = terrain.indexToGeo(feature.centerRow, feature.centerColumn) ?: return@forEach
            val bearing = GeoMath.bearingDeg(here, target)
            val difference = signedBearingDifference(heading, bearing)
            if (abs(difference) > HORIZONTAL_FOV_DEG / 2.0) return@forEach
            val distance = GeoMath.distanceM(here, target)
            val targetElevation = terrain.elevationAt(feature.centerRow.toInt(), feature.centerColumn.toInt())?.toDouble()
            val angle = if (targetElevation != null && altitudeM != null) {
                Math.toDegrees(atan2(targetElevation - altitudeM!!, distance.coerceAtLeast(0.1)))
            } else 0.0
            val x = width / 2f + (difference / HORIZONTAL_FOV_DEG * width).toFloat()
            val y = (horizonY - angle / VERTICAL_FOV_DEG * height).toFloat()
            val radius = (90.0 / hypot(distance, 30.0)).toFloat().coerceIn(18f, 72f)
            canvas.drawCircle(x, y, radius, candidatePaint)
            canvas.drawLine(x, y + radius, x, y + radius + 36f, candidatePaint)
            val label = "${feature.type} · ${distance.toInt()} m · ${(feature.confidence * 100).toInt()}%"
            canvas.drawText(label, (x + radius + 8f).coerceAtMost(width - 420f), y, labelPaint)
        }

        val crosshair = Path().apply {
            moveTo(width / 2f - 24f, height / 2f)
            lineTo(width / 2f + 24f, height / 2f)
            moveTo(width / 2f, height / 2f - 24f)
            lineTo(width / 2f, height / 2f + 24f)
        }
        canvas.drawPath(crosshair, gridPaint)
    }

    private fun signedBearingDifference(center: Double, target: Double): Double =
        ((target - center + 540.0) % 360.0) - 180.0

    companion object {
        private const val HORIZONTAL_FOV_DEG = 62.0
        private const val VERTICAL_FOV_DEG = 48.0
    }
}
