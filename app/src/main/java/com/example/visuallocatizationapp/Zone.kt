package com.example.visuallocatizationapp

import com.google.gson.annotations.SerializedName

data class ZoneBounds(
    @SerializedName("min_lat") val minLat: Double,
    @SerializedName("max_lat") val maxLat: Double,
    @SerializedName("min_lon") val minLon: Double,
    @SerializedName("max_lon") val maxLon: Double
)

data class ZoneCenter(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lon") val lon: Double
)

data class Zone(
    @SerializedName("zone_id") val id: String,
    val name: String,

    @SerializedName("min_zoom") val minZoom: Int,
    @SerializedName("max_zoom") val maxZoom: Int,

    @SerializedName("tile_format") val tileFormat: String,
    @SerializedName("tile_structure") val tileStructure: String,

    val bounds: ZoneBounds,
    val center: ZoneCenter,

    @SerializedName("size_mb") val sizeMb: Int
)

fun Zone.contains(lat: Double, lon: Double): Boolean {
    return lat in bounds.minLat..bounds.maxLat &&
            lon in bounds.minLon..bounds.maxLon
}
