package com.example.visuallocatizationapp.model

import android.graphics.Bitmap
import com.example.visuallocatizationapp.Zone

data class ModelInfo(
    val id: String,
    val version: String,
    val arch: String,
    val engine: String,          // "onnx", "tflite", "torch"
    val inputSize: Int,
    val mean: List<Float>,
    val std: List<Float>,
    val centerCrop: Boolean = true
)

data class PredictionResult(
    val latitude: Double,
    val longitude: Double,
    val confidence: Double
)

sealed interface LocalizationModel {
    suspend fun predict(frames: List<Bitmap>, zone: Zone): PredictionResult
}
