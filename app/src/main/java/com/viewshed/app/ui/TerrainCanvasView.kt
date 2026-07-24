package com.viewshed.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.viewshed.app.viewshed.terrain.ContourSegment
import com.viewshed.app.viewshed.terrain.TerrainAnalysis
import com.viewshed.app.viewshed.terrain.TerrainFeatureCandidate
import com.viewshed.app.viewshed.terrain.TerrainProfilePoint
import com.viewshed.app.viewshed.terrain.TerrainRaster
import kotlin.math.max

enum class TerrainInteractionMode { PAN, PROFILE, MEASURE }

/** Large, zoomable terrain viewport with a lightweight Canvas 3D mesh fallback. */
class TerrainCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var raster: TerrainRaster? = null
    private var image: Bitmap? = null
    private var contours: List<ContourSegment> = emptyList()
    private var candidates: List<TerrainFeatureCandidate> = emptyList()
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var initialized = false
    private var show3d = false
    private var yawDeg = -30f
    private var pitchDeg = 55f
    private var exaggeration = 2.0f
    private var lastTouch = PointF()
    private var firstSelection: PointF? = null
    private var secondSelection: PointF? = null
    private var profile: List<TerrainProfilePoint> = emptyList()
    var interactionMode: TerrainInteractionMode = TerrainInteractionMode.PAN
        set(value) {
            field = value
            firstSelection = null
            secondSelection = null
            profile = emptyList()
            invalidate()
        }
    var onMeasurement: ((distanceM: Double, reliefM: Double) -> Unit)? = null
    var onProfile: ((List<TerrainProfilePoint>) -> Unit)? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 25, 25, 25)
        strokeWidth = 0.8f
        style = Paint.Style.STROKE
    }
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 191, 0)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 225, 229, 232)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val chartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (show3d) {
                exaggeration = (exaggeration * detector.scaleFactor).coerceIn(0.25f, 12f)
            } else {
                imageMatrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
                constrainScale()
            }
            invalidate()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (show3d) {
                yawDeg = -30f
                pitchDeg = 55f
                exaggeration = 2f
            } else resetView()
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (show3d || interactionMode == TerrainInteractionMode.PAN) return false
            selectPoint(e.x, e.y)
            return true
        }
    })

    fun setTerrain(raster: TerrainRaster, bitmap: Bitmap) {
        this.raster = raster
        this.image = bitmap
        initialized = false
        firstSelection = null
        secondSelection = null
        profile = emptyList()
        requestLayout()
        invalidate()
    }

    fun updateImage(bitmap: Bitmap) {
        image = bitmap
        invalidate()
    }

    fun setContours(items: List<ContourSegment>) {
        contours = items
        invalidate()
    }

    fun setCandidates(items: List<TerrainFeatureCandidate>) {
        candidates = items
        invalidate()
    }

    fun setThreeDimensional(enabled: Boolean) {
        show3d = enabled
        invalidate()
    }

    fun setVerticalExaggeration(value: Float) {
        exaggeration = value.coerceIn(0.25f, 12f)
        invalidate()
    }

    fun snapshot(): Bitmap {
        val output = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(output))
        return output
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        initialized = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 18, 23))
        val terrain = raster ?: return
        if (show3d) {
            draw3d(canvas, terrain)
            return
        }
        val bitmap = image ?: return
        if (!initialized) resetView()
        canvas.save()
        canvas.concat(imageMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        contours.forEach { segment ->
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, contourPaint)
        }
        candidates.forEach { candidate ->
            canvas.drawRect(
                candidate.minColumn.toFloat(),
                candidate.minRow.toFloat(),
                candidate.maxColumn.toFloat(),
                candidate.maxRow.toFloat(),
                candidatePaint,
            )
        }
        firstSelection?.let { first ->
            canvas.drawCircle(first.x, first.y, 5f, selectionPaint)
            secondSelection?.let { second ->
                canvas.drawLine(first.x, first.y, second.x, second.y, selectionPaint)
                canvas.drawCircle(second.x, second.y, 5f, selectionPaint)
            }
        }
        canvas.restore()
        if (profile.isNotEmpty()) drawProfileChart(canvas, profile)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && interactionMode == TerrainInteractionMode.PAN) {
                val dx = event.x - lastTouch.x
                val dy = event.y - lastTouch.y
                if (show3d) {
                    yawDeg += dx * 0.35f
                    pitchDeg = (pitchDeg + dy * 0.25f).coerceIn(15f, 82f)
                } else {
                    imageMatrix.postTranslate(dx, dy)
                }
                lastTouch.set(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun selectPoint(viewX: Float, viewY: Float) {
        val terrain = raster ?: return
        imageMatrix.invert(inverseMatrix)
        val point = floatArrayOf(viewX, viewY)
        inverseMatrix.mapPoints(point)
        val selected = PointF(
            point[0].coerceIn(0f, (terrain.width - 1).toFloat()),
            point[1].coerceIn(0f, (terrain.height - 1).toFloat()),
        )
        if (firstSelection == null || secondSelection != null) {
            firstSelection = selected
            secondSelection = null
            profile = emptyList()
        } else {
            val first = firstSelection ?: return
            secondSelection = selected
            val distance = terrain.horizontalDistanceM(first.y.toDouble(), first.x.toDouble(), selected.y.toDouble(), selected.x.toDouble())
            val startElevation = terrain.elevationAt(first.y.toInt(), first.x.toInt()) ?: 0f
            val endElevation = terrain.elevationAt(selected.y.toInt(), selected.x.toInt()) ?: 0f
            if (interactionMode == TerrainInteractionMode.MEASURE) {
                onMeasurement?.invoke(distance, (endElevation - startElevation).toDouble())
            } else {
                profile = TerrainAnalysis.profile(
                    terrain,
                    first.y.toDouble(),
                    first.x.toDouble(),
                    selected.y.toDouble(),
                    selected.x.toDouble(),
                )
                onProfile?.invoke(profile)
            }
        }
        invalidate()
    }

    private fun resetView() {
        val bitmap = image ?: return
        if (width <= 0 || height <= 0) return
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val left = (width - bitmap.width * scale) / 2f
        val top = (height - bitmap.height * scale) / 2f
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(left, top)
        initialized = true
    }

    private fun constrainScale() {
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        val current = values[Matrix.MSCALE_X]
        val bitmap = image ?: return
        val minimum = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * 0.5f
        if (current < minimum) {
            val factor = minimum / current.coerceAtLeast(0.0001f)
            imageMatrix.postScale(factor, factor, width / 2f, height / 2f)
        }
        if (current > 30f) {
            val factor = 30f / current
            imageMatrix.postScale(factor, factor, width / 2f, height / 2f)
        }
    }

    private fun draw3d(canvas: Canvas, terrain: TerrainRaster) {
        val span = (terrain.maxElevationM - terrain.minElevationM).coerceAtLeast(1f)
        val sampleStep = max(1, max(terrain.width, terrain.height) / 100)
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        fun project(row: Int, column: Int): PointF {
            val x = (column.toFloat() / (terrain.width - 1) - 0.5f) * 2f
            val y = (row.toFloat() / (terrain.height - 1) - 0.5f) * 2f
            val z = ((terrain.elevations[row * terrain.width + column] - terrain.minElevationM) / span - 0.5f) *
                0.55f * exaggeration
            val rotatedX = x * kotlin.math.cos(yaw).toFloat() - y * kotlin.math.sin(yaw).toFloat()
            val rotatedY = x * kotlin.math.sin(yaw).toFloat() + y * kotlin.math.cos(yaw).toFloat()
            val pitchedY = rotatedY * kotlin.math.cos(pitch).toFloat() - z * kotlin.math.sin(pitch).toFloat()
            val depth = rotatedY * kotlin.math.sin(pitch).toFloat() + z * kotlin.math.cos(pitch).toFloat()
            val perspective = 1f / (1.65f + depth * 0.32f).coerceAtLeast(0.5f)
            val scale = minOf(width, height) * 0.72f
            return PointF(width / 2f + rotatedX * scale * perspective, height / 2f + pitchedY * scale * perspective)
        }
        val path = Path()
        for (row in 0 until terrain.height step sampleStep) {
            path.reset()
            var first = true
            for (column in 0 until terrain.width step sampleStep) {
                val point = project(row, column)
                if (first) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                first = false
            }
            canvas.drawPath(path, meshPaint)
        }
        for (column in 0 until terrain.width step sampleStep) {
            path.reset()
            var first = true
            for (row in 0 until terrain.height step sampleStep) {
                val point = project(row, column)
                if (first) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                first = false
            }
            canvas.drawPath(path, meshPaint)
        }
    }

    private fun drawProfileChart(canvas: Canvas, points: List<TerrainProfilePoint>) {
        if (points.size < 2) return
        val bounds = RectF(24f, height * 0.72f, width - 24f, height - 24f)
        val background = Paint().apply { color = Color.argb(210, 15, 21, 27); style = Paint.Style.FILL }
        canvas.drawRoundRect(bounds, 14f, 14f, background)
        val minElevation = points.minOf { it.elevationM }
        val maxElevation = points.maxOf { it.elevationM }
        val range = (maxElevation - minElevation).coerceAtLeast(0.1)
        val maxDistance = points.last().distanceM.coerceAtLeast(1.0)
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = bounds.left + (point.distanceM / maxDistance * bounds.width()).toFloat()
            val y = bounds.bottom - ((point.elevationM - minElevation) / range * bounds.height()).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, chartPaint)
    }
}
