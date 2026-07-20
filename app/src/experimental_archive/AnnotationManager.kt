package com.viewshed.app.viewshed

/**
 * Map annotations and comments.
 */
data class MapAnnotation(
    val location: GeoPoint,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val author: String = "User"
)

object AnnotationManager {
    private val annotations = mutableListOf<MapAnnotation>()

    fun addAnnotation(annotation: MapAnnotation) {
        annotations.add(annotation)
    }

    fun getAnnotationsNear(location: GeoPoint, radiusMeters: Double): List<MapAnnotation> {
        return annotations.filter {
            GeoMath.distanceMeters(it.location, location) < radiusMeters
        }
    }
}
