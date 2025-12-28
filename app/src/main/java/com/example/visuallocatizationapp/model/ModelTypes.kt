package com.example.visuallocatizationapp.model

import android.graphics.Bitmap
import com.example.visuallocatizationapp.Zone
import com.google.gson.annotations.SerializedName

data class ModelInfo(
    val id: String,
    val version: String,
    val arch: String,              // e.g., "netvlad", "resnet50", etc.
    val engine: String,            // "onnx"
    @SerializedName("input_size") val inputSize: Int,
    val mean: List<Float>,
    val std: List<Float>,
    @SerializedName("center_crop") val centerCrop: Boolean = true,
    @SerializedName("descriptor_dim") val descriptorDim: Int,
    @SerializedName("input_layout") val inputLayout: String = "nhwc", // or "nchw"
    val database: DatabaseInfo
)

data class DatabaseInfo(
    val file: String,      // e.g., "database_resnet50.bin"
    val index: String,     // e.g., "database_resnet50_index.json"
    val dtype: String,     // "float32" (expected)
    val metric: String     // "cosine"
)

data class DbRow(
    val row: Int,
    val file: String?,
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
