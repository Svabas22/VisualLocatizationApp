package com.example.visuallocatizationapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.visuallocatizationapp.storage.ZoneStorage
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File

@Composable
fun MapOfflineScreen(
    zone: Zone,
    latitude: Double,
    longitude: Double,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Ensure MapLibre is initialised once
    remember { MapLibre.getInstance(context) }

    // Zone folder and style.json
    val zoneDir = remember { ZoneStorage.getZoneDirectory(context, zone.id) }
    val styleFile = remember { File(zoneDir, "style.json") }

    // Load style.json and patch {tileDir} → absolute path
    val styleJson = remember {
        if (styleFile.exists()) {
            styleFile
                .readText()
                .replace("{tileDir}", zoneDir.absolutePath.replace("\\", "/"))
        } else {
            // Minimal fallback style
            """{"version":8,"sources":{},"layers":[]}"""
        }
    }

    val targetLatLng = remember(latitude, longitude) {
        LatLng(latitude, longitude)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        Button(
            onClick = onBack,
            modifier = Modifier.padding(16.dp)
        ) {
            Text("← Back")
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                val mapView = MapView(ctx)
                mapView.onCreate(null)
                mapView.onStart()

                mapView.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(styleJson)) {

                        // Move camera to predicted coordinate
                        val camera = CameraPosition.Builder()
                            .target(targetLatLng)
                            // or some fixed zoom like 16.0
                            .zoom(zone.maxZoom.toDouble() - 1.0)
                            .build()
                        map.cameraPosition = camera

                        // SIMPLE MARKER – no GeoJsonSource, no Builder
                        map.addMarker(
                            MarkerOptions()
                                .position(targetLatLng)
                                .title("Prediction")
                        )
                    }
                }

                mapView
            }
        )
    }
}
