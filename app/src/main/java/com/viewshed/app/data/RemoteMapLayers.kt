package com.viewshed.app.data

import android.content.Context
import com.google.android.gms.maps.model.TileProvider
import com.google.android.gms.maps.model.UrlTileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.pow

enum class RemoteLayerType(val label: String) {
    XYZ("XYZ tiles"),
    WMS("WMS map service"),
    WCS("WCS coverage"),
    ARCGIS("ArcGIS MapServer"),
}

data class RemoteMapLayerSpec(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val type: RemoteLayerType,
    val url: String,
    val layerName: String = "",
    val opacity: Float = 0.75f,
)

class RemoteMapLayerStore(context: Context) {
    private val preferences = context.getSharedPreferences("remote_map_layers", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun list(): List<RemoteMapLayerSpec> = runCatching {
        val type = object : TypeToken<List<RemoteMapLayerSpec>>() {}.type
        gson.fromJson<List<RemoteMapLayerSpec>>(preferences.getString("layers", "[]"), type).orEmpty()
    }.getOrDefault(emptyList())

    fun save(spec: RemoteMapLayerSpec) {
        val layers = list().filterNot { it.id == spec.id } + spec
        preferences.edit().putString("layers", gson.toJson(layers)).apply()
    }

    fun delete(id: String) {
        preferences.edit().putString("layers", gson.toJson(list().filterNot { it.id == id })).apply()
    }
}

object RemoteMapLayers {
    fun provider(spec: RemoteMapLayerSpec): TileProvider {
        validate(spec)
        return object : UrlTileProvider(256, 256) {
            override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? = try {
                URL(
                    when (spec.type) {
                        RemoteLayerType.XYZ -> spec.url
                            .replace("{x}", x.toString())
                            .replace("{y}", y.toString())
                            .replace("{z}", zoom.toString())
                        RemoteLayerType.ARCGIS -> "${spec.url.trimEnd('/')}/tile/$zoom/$y/$x"
                        RemoteLayerType.WMS -> wmsUrl(spec, x, y, zoom)
                        RemoteLayerType.WCS -> wcsUrl(spec, x, y, zoom)
                    },
                )
            } catch (_: MalformedURLException) {
                null
            }
        }
    }

    fun validate(spec: RemoteMapLayerSpec) {
        require(spec.name.isNotBlank()) { "Layer name is required" }
        require(spec.url.startsWith("https://") || spec.url.startsWith("http://")) {
            "Layer URL must start with http:// or https://"
        }
        if (spec.type == RemoteLayerType.XYZ) {
            require(listOf("{x}", "{y}", "{z}").all(spec.url::contains)) {
                "XYZ URL must contain {z}, {x}, and {y}"
            }
        }
        if (spec.type == RemoteLayerType.WMS) require(spec.layerName.isNotBlank()) { "WMS layer name is required" }
        if (spec.type == RemoteLayerType.WCS) require(spec.layerName.isNotBlank()) { "WCS coverage name is required" }
    }

    private fun wmsUrl(spec: RemoteMapLayerSpec, x: Int, y: Int, zoom: Int): String {
        val (minX, minY, maxX, maxY) = webMercatorBounds(x, y, zoom)
        val separator = if ('?' in spec.url) '&' else '?'
        return spec.url + separator + listOf(
            "service=WMS",
            "request=GetMap",
            "version=1.1.1",
            "layers=${URLEncoder.encode(spec.layerName, "UTF-8")}",
            "styles=",
            "format=image/png",
            "transparent=true",
            "srs=EPSG:3857",
            "width=256",
            "height=256",
            "bbox=$minX,$minY,$maxX,$maxY",
        ).joinToString("&")
    }

    /**
     * WCS 1.0 coverage tiles. Servers that advertise image/png can be viewed directly;
     * GeoTIFF-only coverages remain available through the same endpoint for desktop GIS.
     */
    private fun wcsUrl(spec: RemoteMapLayerSpec, x: Int, y: Int, zoom: Int): String {
        val (minX, minY, maxX, maxY) = webMercatorBounds(x, y, zoom)
        val separator = if ('?' in spec.url) '&' else '?'
        return spec.url + separator + listOf(
            "service=WCS",
            "request=GetCoverage",
            "version=1.0.0",
            "coverage=${URLEncoder.encode(spec.layerName, "UTF-8")}",
            "format=image/png",
            "crs=EPSG:3857",
            "response_crs=EPSG:3857",
            "width=256",
            "height=256",
            "bbox=$minX,$minY,$maxX,$maxY",
        ).joinToString("&")
    }

    private fun webMercatorBounds(x: Int, y: Int, zoom: Int): DoubleArray {
        val world = 20_037_508.342789244
        val tiles = 2.0.pow(zoom)
        val tileSpan = world * 2.0 / tiles
        val minX = -world + x * tileSpan
        val maxX = minX + tileSpan
        val maxY = world - y * tileSpan
        val minY = maxY - tileSpan
        return doubleArrayOf(minX, minY, maxX, maxY)
    }
}
