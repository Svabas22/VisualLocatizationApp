package com.example.visuallocatizationapp.model

import android.content.Context
import android.util.Log
import com.example.visuallocatizationapp.Zone
import com.google.gson.Gson
import java.io.File

data class LoadedModel(
    val info: ModelInfo,
    val weightsFile: File,
    val impl: LocalizationModel
)

object ModelLoader {
    private const val TAG = "ModelLoader"

    fun load(context: Context, zone: Zone): LoadedModel? {
        val zoneDir = File(context.filesDir, "zones/${zone.id}")
        val metaFile = File(zoneDir, "model/metadata.json")
        val weightsOnnx = File(zoneDir, "model/weights.onnx")
        val weightsTflite = File(zoneDir, "model/weights.tflite")
        val weightsTorch = File(zoneDir, "model/weights.pt")

        if (!metaFile.exists()) {
            Log.w(TAG, "metadata.json not found for zone ${zone.id}")
            return null
        }

        val meta = runCatching {
            Gson().fromJson(metaFile.readText(), ModelInfo::class.java)
        }.getOrElse {
            Log.e(TAG, "Failed to parse metadata.json", it)
            return null
        }

        val weights = when {
            weightsOnnx.exists() -> weightsOnnx
            weightsTflite.exists() -> weightsTflite
            weightsTorch.exists() -> weightsTorch
            else -> {
                Log.e(TAG, "No weights file found in model/ for zone ${zone.id}")
                return null
            }
        }

        val impl: LocalizationModel = when (meta.engine.lowercase()) {
            "onnx" -> StubLocalizationModel(meta) // TODO: replace with real ONNX impl
            "tflite" -> StubLocalizationModel(meta) // TODO: replace with real TFLite impl
            "torch" -> StubLocalizationModel(meta) // TODO: replace with real Torch impl
            else -> {
                Log.e(TAG, "Unsupported engine: ${meta.engine}")
                return null
            }
        }

        return LoadedModel(meta, weights, impl)
    }
}

/**
 * Temporary stub: exercises preprocessing but returns fake coordinates.
 * Replace with a real engine (ONNXRuntime/TFLite/PyTorch Mobile) when ready.
 */
class StubLocalizationModel(private val info: ModelInfo) : LocalizationModel {
    override suspend fun predict(
        frames: List<android.graphics.Bitmap>,
        zone: Zone
    ): PredictionResult {
        val subset = frames.take(4)
        subset.forEach {
            preprocessFrame(
                it,
                inputSize = info.inputSize,
                mean = info.mean.toFloatArray(),
                std = info.std.toFloatArray(),
                centerCrop = info.centerCrop
            )
        }

        val lat = zone.center.lat
        val lon = zone.center.lon
        val conf = 0.50
        return PredictionResult(lat, lon, conf)
    }
}
