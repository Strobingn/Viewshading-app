package com.viewshed.app.viewshed

/**
 * Attach field notes to locations.
 */
object FieldNotesManager {
    private val notes = mutableMapOf<GeoPoint, String>()

    fun addNote(location: GeoPoint, note: String) {
        notes[location] = note
    }

    fun getNote(location: GeoPoint): String? = notes[location]
}
