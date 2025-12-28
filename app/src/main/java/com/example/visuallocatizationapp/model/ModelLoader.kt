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
import java.nio.FloatBuffer
import kotlin.math.sqrt

data class LoadedModel(
    val info: ModelInfo,
    val session: OrtSession?,
    val db: FloatArray?,
    val dbRows: List<DbRow>
)

object ModelLoader {
    private const val TAG = "ModelLoader"

    fun load(context: Context, zone: Zone): LoadedModel? {
        val zoneDir = File(context.filesDir, "zones/${zone.id}")
        val metaFile = findFirst(
            listOf(
                File(zoneDir, "model/metadata.json"),
                File(zoneDir, "model/metadata_resnet50.json")
            )
        ) ?: return null.also { Log.w(TAG, "No metadata found for ${zone.id}") }

        val meta = runCatching {
            Gson().fromJson(metaFile.readText(), ModelInfo::class.java)
        }.getOrElse {
            Log.e(TAG, "Failed to parse metadata", it)
            return null
        }

        val weightsFile = findFirst(
            listOf(
                File(zoneDir, "model/weights.onnx"),
                File(zoneDir, "model/weights_resnet50.onnx")
            )
        )

        val session = if (meta.engine.equals("onnx", ignoreCase = true) && weightsFile != null) {
            runCatching {
                OrtEnvironment.getEnvironment().createSession(weightsFile.absolutePath, OrtSession.SessionOptions())
            }.getOrElse {
                Log.e(TAG, "Failed to load ONNX session", it)
                null
            }
        } else null

        val (db, rows) = loadDb(zoneDir, meta)

        return LoadedModel(meta, session, db, rows)
    }

    private fun findFirst(files: List<File>): File? = files.firstOrNull { it.exists() }

    private fun loadDb(zoneDir: File, meta: ModelInfo): Pair<FloatArray?, List<DbRow>> {
        val dbInfo = meta.database ?: return null to emptyList()
        val dbFile = File(zoneDir, "model/${dbInfo.file}")
        val idxFile = File(zoneDir, "model/${dbInfo.index}")
        if (!dbFile.exists() || !idxFile.exists()) return null to emptyList()

        val bytes = dbFile.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        buf.asFloatBuffer().get(out)

        val type = object : TypeToken<List<DbRow>>() {}.type
        val rows: List<DbRow> = Gson().fromJson(idxFile.readText(), type)
        return out to rows
    }
}

class OnnxLocalizationModel(private val loaded: LoadedModel) : LocalizationModel {
    private val session = loaded.session
    private val env: OrtEnvironment? = session?.environment
    private val info = loaded.info
    private val db = loaded.db
    private val rows = loaded.dbRows
    private val dim = if (info.descriptorDim > 0) info.descriptorDim else db?.let { it.size / (rows.size.coerceAtLeast(1)) } ?: 0

    override suspend fun predict(frames: List<android.graphics.Bitmap>, zone: Zone): PredictionResult {
        if (session == null) {
            return PredictionResult(zone.center.lat, zone.center.lon, 0.0)
        }
        val subset = frames.take(4)
        val descriptors = subset.mapNotNull { runEncoder(it) }
        if (descriptors.isEmpty()) return PredictionResult(zone.center.lat, zone.center.lon, 0.0)
        val query = averageAndNormalize(descriptors)

        if (db == null || rows.isEmpty() || dim == 0) {
            return PredictionResult(zone.center.lat, zone.center.lon, 0.0)
        }
        val (bestIdx, bestSim) = top1Cosine(query, db, dim)
        val row = rows.getOrNull(bestIdx) ?: return PredictionResult(zone.center.lat, zone.center.lon, 0.0)
        return PredictionResult(row.lat, row.lon, bestSim.toDouble())
    }

    private fun runEncoder(bmp: android.graphics.Bitmap): FloatArray? {
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

        val environment = env ?: return null

        return runCatching {
            val fb: FloatBuffer = FloatBuffer.wrap(input)
            OnnxTensor.createTensor(environment, fb, shape).use { tensor ->
                session!!.run(mapOf(session.inputNames.first() to tensor)).use { results ->
                    val out = results.first().value
                    when (out) {
                        is Array<*> -> {
                            val first = out.firstOrNull()
                            when (first) {
                                is FloatArray -> l2Normalize(first)
                                is Array<*> -> (first as? Array<*>)?.firstOrNull()?.let { arr ->
                                    (arr as? FloatArray)?.let { l2Normalize(it) }
                                }
                                else -> null
                            }
                        }
                        is FloatArray -> l2Normalize(out)
                        else -> null
                    }
                }
            }
        }.getOrNull()
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0f
        for (v in vec) sum += v * v
        val inv = if (sum > 0f) 1f / sqrt(sum) else 1f
        return FloatArray(vec.size) { i -> vec[i] * inv }
    }

    private fun averageAndNormalize(vectors: List<FloatArray>): FloatArray {
        val out = FloatArray(vectors.first().size)
        for (v in vectors) {
            for (i in v.indices) out[i] += v[i]
        }
        val invN = 1f / vectors.size
        for (i in out.indices) out[i] *= invN
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