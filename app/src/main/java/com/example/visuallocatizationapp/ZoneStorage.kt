package com.example.visuallocatizationapp.storage

import android.content.Context
import android.util.Log
import com.example.visuallocatizationapp.Zone
import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

object ZoneStorage {
    private const val TAG = "ZoneStorage"
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

    fun getZoneDirectory(context: Context, zoneId: String): File {
        return getZoneDir(context, zoneId)
    }

    fun isZoneDownloaded(context: Context, zoneId: String): Boolean {
        return getZoneDir(context, zoneId).exists()
    }

    fun saveZoneZip(context: Context, zoneId: String, inputStream: InputStream) {
        try {
            val zonesDir = getZonesDir(context)
            Log.d("ZONE_PATH", "filesDir = ${context.filesDir.absolutePath}")
            Log.d("ZONE_PATH", "zones    = ${zonesDir.absolutePath}")

            val zipFile = File(zonesDir, "$zoneId.zip")

            // Save ZIP file
            inputStream.use { inp ->
                zipFile.outputStream().use { out ->
                    inp.copyTo(out)
                }
            }

            // Extract ZIP into zones/<zoneId>/
            unzip(zipFile, getZoneDir(context, zoneId))

            // Remove ZIP after extraction
            zipFile.delete()

            Log.d(TAG, "Zone $zoneId extracted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save zone zip", e)
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val entryDest = File(targetDir, entry.name)

                if (entry.isDirectory) {
                    entryDest.mkdirs()
                } else {
                    entryDest.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        entryDest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d("DEBUG-ZIP", "Unzipping entry: ${entry.name}")
                }
            }
        }

        // Log final folder listing for sanity
        val contents = targetDir.list()?.joinToString(", ") ?: ""
        Log.d("DEBUG-ZIP", "Extracted zone folder content: $contents")
    }

    fun listDownloadedZones(context: Context): List<String> {
        val root = getZonesDir(context)
        return root.list()?.toList() ?: emptyList()
    }

    fun deleteZone(context: Context, zoneId: String) {
        val dir = getZoneDir(context, zoneId)
        dir.deleteRecursively()
    }

    fun getZoneJson(context: Context, zoneId: String): Zone? {
        val file = File(getZoneDir(context, zoneId), "zone.json")
        if (!file.exists()) return null
        return Gson().fromJson(file.readText(), Zone::class.java)
    }
}
