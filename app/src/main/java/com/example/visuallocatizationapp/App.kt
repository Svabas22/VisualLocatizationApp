package com.example.visuallocatizationapp

import android.app.Application
import org.maplibre.android.MapLibre

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Minimal initialization for offline-only mode
        MapLibre.getInstance(this)
    }
}
