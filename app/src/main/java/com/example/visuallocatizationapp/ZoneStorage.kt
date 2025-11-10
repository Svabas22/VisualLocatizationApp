package com.example.visuallocatizationapp.storage

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream

object ZoneStorage {
    private const val ZONES_DIR = "zones"

    fun getZonesDir(context: Context): File {
        val dir = File(context.filesDir, ZONES_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getZoneDir(context: Context, zoneId: String): File {
        val dir = File(getZonesDir(context), zoneId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isZoneDownloaded(context: Context, zoneId: String): Boolean {
        val dir = getZoneDir(context, zoneId)
        val zoneFile = File(dir, "zone.json")
        return zoneFile.exists()
    }

    fun saveZoneFile(context: Context, zoneId: String, inputStream: InputStream) {
        try {
            val dir = getZoneDir(context, zoneId)
            val outFile = File(dir, "zone.json")
            outFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            Log.d("ZoneStorage", "Saved zone data to ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("ZoneStorage", "Failed to save zone $zoneId", e)
        }
    }

    fun listDownloadedZones(context: Context): List<String> {
        val dir = getZonesDir(context)
        return dir.list()?.toList() ?: emptyList()
    }
}
