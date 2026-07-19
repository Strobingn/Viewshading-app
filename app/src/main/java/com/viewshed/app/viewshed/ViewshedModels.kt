package com.viewshed.app.viewshed

data class ViewshedParams(
    val eyeHeightM: Double = 1.7,
    val targetHeightM: Double = 0.0,
    val maxDistKm: Double = 5.0,
    val numRays: Int = 72,
    val samplesPerRay: Int = 80,
    val useDemoTerrain: Boolean = true,
    val useCurvature: Boolean = true,
    val refraction: Double = 0.13
) {
    fun sanitized(): ViewshedParams = copy(
        eyeHeightM = eyeHeightM.coerceIn(0.0, 100.0),
        targetHeightM = targetHeightM.coerceIn(0.0, 200.0),
        maxDistKm = maxDistKm.coerceIn(0.1, 50.0),
        numRays = numRays.coerceIn(8, 360),
        samplesPerRay = samplesPerRay.coerceIn(10, 200),
        refraction = refraction.coerceIn(0.0, 1.0)
    )
}

data class ViewshedStats(
    val boundaryPoints: Int,
    val maxRangeM: Double,
    val avgRangeM: Double,
    val areaKm2: Double,
    val numRays: Int,
    val samplesPerRay: Int
) {
    val maxRangeKm: Double get() = maxRangeM / 1000.0
    val avgRangeKm: Double get() = avgRangeM / 1000.0
}

data class ViewshedResult(
    val observer: GeoPoint,
    val boundary: List<GeoPoint>,
    val rangesM: List<Double>,
    val stats: ViewshedStats,
    val params: ViewshedParams
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
            samplesPerRay = samples
        )
}
