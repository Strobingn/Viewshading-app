package com.viewshed.app.viewshed

/**
 * Radial line-of-sight viewshed using the horizon (max elevation-angle) method.
 *
 * Along each ray, a sample is visible if its elevation angle from the eye is
 * greater than or equal to the running maximum elevation angle of nearer samples.
 */
object ViewshedEngine {

    fun compute(
        observer: GeoPoint,
        params: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double = { DemoTerrain.elevation(it) },
        onRayProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ViewshedResult {
        val p = params.sanitized()
        val maxDistM = p.maxDistKm * 1000.0
        val observerGround = elevations[observer.key()] ?: demoElev(observer)
        val eyeElev = observerGround + p.eyeHeightM

        val boundary = ArrayList<GeoPoint>(p.numRays + 1)
        val ranges = ArrayList<Double>(p.numRays)

        for (i in 0 until p.numRays) {
            val bearing = i * 360.0 / p.numRays
            var maxAngle = Double.NEGATIVE_INFINITY
            var maxVisibleDist = 0.0

            for (s in 1..p.samplesPerRay) {
                val distM = s * maxDistM / p.samplesPerRay
                val target = GeoMath.destination(observer, bearing, distM)
                val ground = elevations[target.key()] ?: demoElev(target)
                val targetElev = ground + p.targetHeightM
                val angle = GeoMath.elevationAngleRad(
                    observerElev = eyeElev,
                    targetElev = targetElev,
                    distM = distM,
                    useCurvature = p.useCurvature,
                    refractionCoeff = p.refraction
                )
                // Visible if not below the current horizon angle
                if (angle >= maxAngle - 1e-12) {
                    maxVisibleDist = distM
                }
                if (angle > maxAngle) {
                    maxAngle = angle
                }
            }

            ranges.add(maxVisibleDist)
            if (maxVisibleDist > 0) {
                boundary.add(GeoMath.destination(observer, bearing, maxVisibleDist))
            } else {
                boundary.add(observer)
            }
            onRayProgress?.invoke(i + 1, p.numRays)
        }

        if (boundary.isNotEmpty()) {
            boundary.add(boundary.first())
        }

        val positive = ranges.filter { it > 0 }
        val stats = ViewshedStats(
            boundaryPoints = boundary.size,
            maxRangeM = ranges.maxOrNull() ?: 0.0,
            avgRangeM = if (positive.isEmpty()) 0.0 else positive.average(),
            areaKm2 = GeoMath.polygonAreaKm2(boundary),
            numRays = p.numRays,
            samplesPerRay = p.samplesPerRay
        )

        return ViewshedResult(
            observer = observer,
            boundary = boundary,
            rangesM = ranges,
            stats = stats,
            params = p
        )
    }

    /** All sample points needed for a calculation (for elevation pre-fetch). */
    fun samplePoints(observer: GeoPoint, params: ViewshedParams): List<GeoPoint> {
        val p = params.sanitized()
        val maxDistM = p.maxDistKm * 1000.0
        val out = ArrayList<GeoPoint>(p.numRays * p.samplesPerRay + 1)
        out.add(observer)
        for (i in 0 until p.numRays) {
            val bearing = i * 360.0 / p.numRays
            for (s in 1..p.samplesPerRay) {
                val distM = s * maxDistM / p.samplesPerRay
                out.add(GeoMath.destination(observer, bearing, distM))
            }
        }
        return out
    }
}
