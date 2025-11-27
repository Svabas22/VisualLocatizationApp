package com.example.visuallocatizationapp

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.visuallocatizationapp.storage.ZoneStorage
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File

@SuppressLint("MissingPermission")
@Composable
fun MapOfflineScreen(
    zone: Zone,
    latitude: Double,
    longitude: Double,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    remember { MapLibre.getInstance(context) }

    val zoneDir = remember { ZoneStorage.getZoneDirectory(context, zone.id) }
    val styleFile = File(zoneDir, "style.json")

    val styleJson = remember {
        if (styleFile.exists()) {
            styleFile.readText()
                .replace("{tileDir}", zoneDir.absolutePath.replace("\\", "/"))
        } else """{"version":8,"sources":{},"layers":[]}"""
    }

    val targetLatLng = LatLng(latitude, longitude)

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }

    DisposableEffect(lifecycleOwner) {

        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { mapView.onStart() }
            override fun onResume(owner: LifecycleOwner) { mapView.onResume() }
            override fun onPause(owner: LifecycleOwner) { mapView.onPause() }
            override fun onStop(owner: LifecycleOwner) { mapView.onStop() }
            override fun onDestroy(owner: LifecycleOwner) { mapView.onDestroy() }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Button(
            onClick = onBack,
            modifier = Modifier.padding(16.dp)
        ) { Text("← Back") }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->

                view.getMapAsync { map ->

                    map.setStyle(Style.Builder().fromJson(styleJson)) {

                        val camera = CameraPosition.Builder()
                            .target(targetLatLng)
                            .zoom(zone.maxZoom.toDouble() - 1.0)
                            .build()

                        map.cameraPosition = camera

                        map.addMarker(
                            MarkerOptions()
                                .position(targetLatLng)
                                .title("Prediction")
                        )
                    }
                }
            }
        )
    }
}
