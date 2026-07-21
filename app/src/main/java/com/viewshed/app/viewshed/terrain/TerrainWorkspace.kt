package com.viewshed.app.viewshed.terrain

/** Process-local handoff between the map and the full-screen terrain workstation. */
object TerrainWorkspace {
    @Volatile
    var current: TerrainRaster? = null
}
