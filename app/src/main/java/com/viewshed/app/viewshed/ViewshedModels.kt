package com.viewshed.app.viewshed

data class ViewshedParams(
    val eyeHeightM: Double = 1.7,
    val targetHeightM: Double = 0.0,
    val maxDistKm: Double = 5.0,
    val numRays: Int = 72,
    val samplesPerRay: Int = 80,
    val useDemoTerrain: Boolean = true,
    val useCurvature: Boolean = true,
    val refraction: Double = 0.13,
    val parallelRays: Boolean = true,
    /** Retained for saved-session compatibility; fixed-grid analysis disables it. */
    val adaptiveSampling: Boolean = false,
    /** Retained for saved-session compatibility; terrain visibility is not monotonic. */
    val binarySearchHorizon: Boolean = false,
    val quality: SampleQuality = SampleQuality.MEDIUM
) {
    fun sanitized(): ViewshedParams = copy(
        eyeHeightM = eyeHeightM.coerceIn(0.0, 100.0),
        targetHeightM = targetHeightM.coerceIn(0.0, 200.0),
        maxDistKm = maxDistKm.coerceIn(0.1, 50.0),
        numRays = numRays.coerceIn(8, 360),
        samplesPerRay = samplesPerRay.coerceIn(10, 250),
        refraction = refraction.coerceIn(0.0, 0.99),
        adaptiveSampling = false,
        binarySearchHorizon = false
    )

    fun withQuality(q: SampleQuality): ViewshedParams = copy(
        quality = q,
        numRays = q.rays,
        samplesPerRay = q.samples
    )
}

enum class SampleQuality(val label: String, val rays: Int, val samples: Int) {
    LOW("Fast", 36, 40),
    MEDIUM("Balanced", 72, 80),
    HIGH("Detailed", 120, 120)
}

/** Visibility of one terrain/target sample along a ray. */
data class VisibilitySample(
    val point: GeoPoint,
    val distanceM: Double,
    val terrainElevationM: Double,
    val terrainAngleRad: Double,
    val targetAngleRad: Double,
    val visible: Boolean
)

data class VisibilityRay(
    val bearingDeg: Double,
    val samples: List<VisibilitySample>,
    val farthestVisibleM: Double
)

/**
 * One contiguous visible run within a ray's angular wedge.
 * Multiple sectors on a ray preserve hidden valleys followed by visible peaks.
 */
data class VisibleSector(
    val bearingStartDeg: Double,
    val bearingEndDeg: Double,
    val innerDistanceM: Double,
    val outerDistanceM: Double,
    val visibleCellCount: Int,
    val areaM2: Double,
    val boundary: List<GeoPoint>
)

data class ViewshedStats(
    val boundaryPoints: Int,
    val maxRangeM: Double,
    val avgRangeM: Double,
    val areaKm2: Double,
    val numRays: Int,
    val samplesPerRay: Int,
    val visibleCells: Int = 0,
    val totalCells: Int = 0
) {
    val maxRangeKm: Double get() = maxRangeM / 1000.0
    val avgRangeKm: Double get() = avgRangeM / 1000.0
}

data class ViewshedResult(
    val observer: GeoPoint,
    /** Outer extent only; use [visibleSectors] for the actual visible mask. */
    val boundary: List<GeoPoint>,
    val rangesM: List<Double>,
    val stats: ViewshedStats,
    val params: ViewshedParams,
    val visibilityRays: List<VisibilityRay> = emptyList(),
    val visibleSectors: List<VisibleSector> = emptyList()
)

enum class AnalysisPreset(
    val label: String,
    val eyeHeightM: Double,
    val targetHeightM: Double,
    val maxDistKm: Double,
    val numRays: Int,
    val samples: Int
) {
    TREE_STAND("Tree stand", 5.0, 1.0, 1.5, 90, 60),
    KAYAK("Kayak 2 km", 1.2, 0.5, 2.0, 72, 70),
    RIDGE("Ridge 10 km", 1.7, 0.0, 10.0, 120, 100),
    CUSTOM("Custom", 1.7, 0.0, 5.0, 72, 80);

    fun toParams(base: ViewshedParams = ViewshedParams()): ViewshedParams =
        base.copy(
            eyeHeightM = eyeHeightM,
            targetHeightM = targetHeightM,
            maxDistKm = maxDistKm,
            numRays = numRays,
            samplesPerRay = samples,
            quality = SampleQuality.MEDIUM
        )
}
