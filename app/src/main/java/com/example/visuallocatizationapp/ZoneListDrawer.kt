package com.example.visuallocatizationapp

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.visuallocatizationapp.network.ApiClient
import com.example.visuallocatizationapp.storage.ZoneStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.ResponseBody

@Composable
fun ZoneListDrawer(onZoneSelected: (Zone) -> Unit) {
    val context = LocalContext.current
    var zones by remember { mutableStateOf<List<Zone>>(emptyList()) }
    var downloadedZones by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            val response = ApiClient.instance.getZones()
            if (response.isSuccessful) {
                zones = response.body() ?: emptyList()
            }
            downloadedZones = ZoneStorage.listDownloadedZones(context)
        } catch (e: Exception) {
            Log.e("Zones", "Error fetching zones", e)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items(zones) { zone ->
            val isDownloaded = downloadedZones.contains(zone.id)
            ZoneDrawerItem(
                zone = zone,
                isDownloaded = isDownloaded,
                onClick = {
                    if (!isDownloaded) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val response = ApiClient.instance.downloadZone(zone.id)
                            if (response.isSuccessful) {
                                val body: ResponseBody = response.body()!!
                                ZoneStorage.saveZoneFile(context, zone.id, body.byteStream())
                                downloadedZones = ZoneStorage.listDownloadedZones(context)
                            }
                        }
                    } else {
                        onZoneSelected(zone)
                    }
                }
            )
        }
    }
}

@Composable
fun ZoneDrawerItem(zone: Zone, isDownloaded: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        color = if (isDownloaded) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(zone.name, style = MaterialTheme.typography.titleMedium)
            Text("Size: ${zone.size_mb} MB", style = MaterialTheme.typography.bodySmall)
            if (isDownloaded) {
                Text("✓ Downloaded", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
