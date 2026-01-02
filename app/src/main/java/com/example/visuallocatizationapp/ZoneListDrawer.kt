package com.example.visuallocatizationapp

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.visuallocatizationapp.ZoneStorage
import com.example.visuallocatizationapp.network.ApiClient
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody

@Composable
fun ZoneListDrawer(
    selectedZoneId: String?,
    onZoneSelected: (Zone) -> Unit
) {
    val context = LocalContext.current
    var zones by remember { mutableStateOf<List<Zone>>(emptyList()) }
    var downloadedZones by remember { mutableStateOf<List<String>>(emptyList()) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    fun Zone.toSafe(): Zone? {
        val safeId = runCatching { id }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val safeName = runCatching { name }.getOrNull().orEmpty().ifBlank { "Unknown zone" }
        val safeDownloadUrl = runCatching { downloadUrl }.getOrNull()
        return copy(id = safeId, name = safeName, downloadUrl = safeDownloadUrl)
    }

    fun JsonElement?.asZoneListOrEmpty(): List<Zone> {
        if (this == null) return emptyList()
        val listType = object : TypeToken<List<Zone>>() {}.type

        return when {
            isJsonArray -> gson.fromJson(this, listType)
            isJsonObject -> {
                // Try direct single-zone parse
                val zone = runCatching { gson.fromJson(this, Zone::class.java) }.getOrNull()
                if (zone != null) listOf(zone) else emptyList()
            }
            isJsonPrimitive && asJsonPrimitive.isString -> {
                val inner = asString?.trim()
                if (!inner.isNullOrBlank() && (inner.startsWith("{") || inner.startsWith("["))) {
                    runCatching { gson.fromJson(inner, JsonElement::class.java) }
                        .getOrNull()
                        .asZoneListOrEmpty()
                } else emptyList()
            }
            else -> emptyList()
        }
    }

    fun parseZones(json: String?): List<Zone> {
        if (json.isNullOrBlank()) return emptyList()

        fun parseElement(elem: JsonElement): List<Zone> {
            val listType = object : TypeToken<List<Zone>>() {}.type

            return when {
                elem.isJsonArray -> gson.fromJson(elem, listType)
                elem.isJsonObject && elem.asJsonObject.has("zones") -> {
                    val zonesElement = elem.asJsonObject.get("zones")
                    when {
                        zonesElement != null && zonesElement.isJsonArray -> gson.fromJson(zonesElement, listType)
                        zonesElement != null && zonesElement.isJsonObject -> zonesElement.asZoneListOrEmpty()
                        zonesElement != null && zonesElement.isJsonPrimitive && zonesElement.asJsonPrimitive.isString ->
                            zonesElement.asZoneListOrEmpty()
                        else -> emptyList()
                    }
                }
                elem.isJsonObject -> {
                    // Either a single zone object, or a map keyed by id => map values
                    val direct = runCatching { gson.fromJson(elem, Zone::class.java) }.getOrNull()
                    if (direct != null && !direct.id.isNullOrBlank()) listOf(direct) else
                        elem.asJsonObject.entrySet().flatMap { (_, value) ->
                            value.asZoneListOrEmpty()
                        }
                }
                elem.isJsonPrimitive && elem.asJsonPrimitive.isString -> {
                    val inner = elem.asString?.trim()
                    if (!inner.isNullOrBlank() && (inner.startsWith("{") || inner.startsWith("["))) {
                        val innerElem = runCatching { gson.fromJson(inner, JsonElement::class.java) }.getOrNull()
                        if (innerElem != null) return parseElement(innerElem)
                    }
                    emptyList()
                }
                else -> emptyList()
            }
        }

        return try {
            val root = gson.fromJson(json, JsonElement::class.java) ?: return emptyList()
            parseElement(root)
        } catch (e: Exception) {
            Log.e("Zones", "Failed to parse zones payload", e)
            emptyList()
        }
    }

    suspend fun refresh() {
        val newDownloadedZones = withContext(Dispatchers.IO) {
            ZoneStorage.listDownloadedZones(context)
        }

        val localZones = newDownloadedZones.mapNotNull { zoneId ->
            ZoneStorage.getZoneJson(context, zoneId)
        }

        val newZones = try {
            val response = withContext(Dispatchers.IO) { ApiClient.instance.getZones() }
            if (response.isSuccessful) {
                val bodyString: String? = response.body()?.string()
                if (bodyString.isNullOrBlank()) {
                    Log.e("Zones", "Zones response was empty")
                    emptyList()
                } else {
                    Log.d("Zones", "Zones response (first 300 chars): ${bodyString.take(300)}")
                    parseZones(bodyString)
                }
            } else {
                val err: String? = response.errorBody()?.string()
                Log.e("Zones", "Failed to fetch zones: ${response.code()} $err")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("Zones", "Error fetching zones", e)
            emptyList()
        }


        val mergedZones = (newZones + localZones).associateBy { it.id }.values.toList()

        val sanitized = mergedZones.mapNotNull {
            val safe = it.toSafe()
            if (safe == null) {
                Log.e("Zones", "Dropping zone with missing/invalid id or name: $it")
            }
            safe
        }

        zones = sanitized
        downloadedZones = newDownloadedZones
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zones", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { scope.launch { refresh() } }) {
                    Text("Refresh")
                }
            }
            downloadStatus?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(zones) { zone ->
            val isDownloaded = downloadedZones.contains(zone.id)
            val isSelected = zone.id == selectedZoneId

            ZoneDrawerItem(
                zone = zone,
                isDownloaded = isDownloaded,
                isSelected = isSelected,
                onClick = {
                    if (isDownloaded) {
                        onZoneSelected(zone)
                    } else {
                        scope.launch {
                            val downloadUrl = zone.downloadUrl ?: "${ApiClient.baseUrl}${zone.id}.zip"
                            if (downloadUrl.isBlank()) {
                                Log.e("ZoneDownload", "No download URL available for zone ${zone.id}")
                                return@launch
                            }

                            try {
                                downloadStatus = "Downloading ${zone.name}..."
                                val response = withContext(Dispatchers.IO) {
                                    ApiClient.instance.downloadZone(downloadUrl)
                                }
                                if (response.isSuccessful) {
                                    val body: ResponseBody = response.body()!!
                                    withContext(Dispatchers.IO) {
                                        ZoneStorage.saveZoneZip(
                                            context,
                                            zone.id,
                                            body.byteStream()
                                        )
                                    }
                                    refresh()
                                } else {
                                    Log.e("ZoneDownload", "Server error: ${response.code()}")
                                }
                            } catch (e: Exception) {
                                Log.e("ZoneDownload", "Failed", e)
                            } finally {
                                downloadStatus = null
                            }
                        }
                    }
                },
                onDelete = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ZoneStorage.deleteZone(context, zone.id)
                        }
                        refresh()
                    }
                }
            )
        }
    }
}

@Composable
fun ZoneDrawerItem(
    zone: Zone,
    isDownloaded: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        color = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isDownloaded -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(zone.name, style = MaterialTheme.typography.titleMedium)
                Text("Size: ${zone.sizeMb} MB", style = MaterialTheme.typography.bodySmall)
                if (isDownloaded)
                    Text("✓ Downloaded", color = MaterialTheme.colorScheme.primary)
            }

            if (isDownloaded) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete zone",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
