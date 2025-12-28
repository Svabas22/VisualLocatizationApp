package com.example.visuallocatizationapp.model

import android.content.Context
import android.util.Log
import com.example.visuallocatizationapp.Zone
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class LoadedModel(
    val info: ModelInfo,
    val db: FloatArray,
    val dbRows: List<DbRow>,
    val session: OrtSession
)

object ModelLoader {
    private const val TAG = "ModelLoader"

    fun load(context: Context, zone: Zone): LoadedModel? {
        val zoneDir = File(context.filesDir, "zones/${zone.id}")
        val metaFile = File(zoneDir, "model/metadata_resnet50.json") // adjust if dynamic
        if (!metaFile.exists()) {
            Log.w(TAG, "metadata not found for zone ${zone.id}")
            return null
        }

        val info = Gson().fromJson(metaFile.readText(), ModelInfo::class.java)

        val dbFile = File(zoneDir, "model/${info.database.file}")
        val idxFile = File(zoneDir, "model/${info.database.index}")
        val weightsFile = File(zoneDir, "model/weights_resnet50.onnx") // adjust if dynamic

        if (!dbFile.exists() || !idxFile.exists() || !weightsFile.exists()) {
            Log.e(TAG, "Model assets missing in zone ${zone.id}")
            return null
        }

        val db = loadDb(dbFile, info.descriptorDim)
        val rows = loadIndex(idxFile)
        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(weightsFile.absolutePath, OrtSession.SessionOptions())

        return LoadedModel(info, db, rows, session)
    }

    private fun loadDb(dbFile: File, dim: Int): FloatArray {
        val bytes = dbFile.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = bytes.size / 4
        val out = FloatArray(count)
        buf.asFloatBuffer().get(out)
        require(count % dim == 0) { "DB length not divisible by descriptor_dim" }
        return out
    }

    private fun loadIndex(idxFile: File): List<DbRow> {
        val type = object : TypeToken<List<DbRow>>() {}.type
        return Gson().fromJson(idxFile.readText(), type)
    }
}

class OnnxLocalizationModel(private val loaded: LoadedModel) : LocalizationModel {
    private val env: OrtEnvironment = loaded.session.environment
    private val info = loaded.info
    private val db = loaded.db
    private val rows = loaded.dbRows
    private val dim = info.descriptorDim

    override suspend fun predict(frames: List<android.graphics.Bitmap>, zone: Zone): PredictionResult {
        // take a few frames to reduce latency
        val subset = frames.take(4)
        val descriptors = subset.map { runEncoder(it) }
        val query = averageAndNormalize(descriptors)
        val (bestIdx, bestSim) = top1Cosine(query, db, dim)
        val row = rows.getOrNull(bestIdx)
            ?: return PredictionResult(zone.center.lat, zone.center.lon, 0.0)
        val lat = row.lat
        val lon = row.lon
        return PredictionResult(lat, lon, bestSim)
    }

    private fun runEncoder(bmp: android.graphics.Bitmap): FloatArray {
        val input = preprocessFrame(
            bmp,
            inputSize = info.inputSize,
            mean = info.mean.toFloatArray(),
            std = info.std.toFloatArray(),
            centerCrop = info.centerCrop,
            inputLayout = info.inputLayout
        )

        val shape = when (info.inputLayout.lowercase()) {
            "nchw" -> longArrayOf(1, 3, info.inputSize.toLong(), info.inputSize.toLong())
            else -> longArrayOf(1, info.inputSize.toLong(), info.inputSize.toLong(), 3)
        }

        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            val results = loaded.session.run(mapOf("input" to tensor))
            val out = results[0].value as Array<FloatArray>
            val vec = out[0]
            return l2Normalize(vec)
        }
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0f
        for (v in vec) sum += v * v
        val inv = if (sum > 0f) 1f / sqrt(sum) else 1f
        return FloatArray(vec.size) { i -> vec[i] * inv }
    }

    private fun averageAndNormalize(vectors: List<FloatArray>): FloatArray {
        val out = FloatArray(dim)
        for (v in vectors) {
            for (i in 0 until dim) out[i] += v[i]
        }
        val invN = 1f / vectors.size
        for (i in 0 until dim) out[i] *= invN
        return l2Normalize(out)
    }

    private fun top1Cosine(query: FloatArray, db: FloatArray, dim: Int): Pair<Int, Float> {
        var bestIdx = -1
        var best = -1f
        val rows = db.size / dim
        var offset = 0
        for (r in 0 until rows) {
            var dot = 0f
            for (i in 0 until dim) {
                dot += query[i] * db[offset + i]
            }
            if (dot > best) {
                best = dot
                bestIdx = r
            }
            offset += dim
        }
        return bestIdx to best
    }
}
