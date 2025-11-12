package com.example.visuallocatizationapp

import android.content.Context
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
import com.example.visuallocatizationapp.network.ApiClient
import com.example.visuallocatizationapp.storage.ZoneStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.ResponseBody


@Composable
fun ZoneListDrawer(
    selectedZoneId: String?,
    onZoneSelected: (Zone) -> Unit
) {
    val context = LocalContext.current
    var zones by remember { mutableStateOf<List<Zone>>(emptyList()) }
    var downloadedZones by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val response = ApiClient.instance.getZones()
            if (response.isSuccessful) zones = response.body() ?: emptyList()
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
            val isSelected = zone.id == selectedZoneId

            ZoneDrawerItem(
                zone = zone,
                isDownloaded = isDownloaded,
                isSelected = isSelected,
                onClick = {
                    if (isDownloaded) {
                        onZoneSelected(zone)
                    } else {
                        scope.launch(Dispatchers.IO) {
                            val response = ApiClient.instance.downloadZone(zone.id)
                            if (response.isSuccessful) {
                                val body: ResponseBody = response.body()!!
                                ZoneStorage.saveZoneFile(context, zone.id, body.byteStream())
                                downloadedZones = ZoneStorage.listDownloadedZones(context)
                            }
                        }
                    }
                },
                onDelete = {
                    scope.launch(Dispatchers.IO) {
                        ZoneStorage.deleteZone(context, zone.id)
                        downloadedZones = ZoneStorage.listDownloadedZones(context)
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
                Text("Size: ${zone.size_mb} MB", style = MaterialTheme.typography.bodySmall)
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

