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
    val centerCrop: Boolean = true,
    val descriptorDim: Int = 0,
    val inputLayout: String = "nhwc",
    val database: DatabaseInfo? = null
)

data class DatabaseInfo(
    val file: String,
    val index: String,
    val dtype: String = "float32",
    val metric: String = "cosine"
)

data class DbRow(
    val row: Int,
    val file: String? = null,
    val lat: Double,
    val lon: Double
)

data class PredictionResult(
    val latitude: Double,
    val longitude: Double,
    val confidence: Double
)

sealed interface LocalizationModel {
    suspend fun predict(frames: List<Bitmap>, zone: Zone): PredictionResult
}
