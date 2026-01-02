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
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

private const val TAG = "ModelLoader"

data class LoadedModel(
    val info: ModelInfo,
    val session: OrtSession?,
    val db: FloatBuffer?,
    val dbRows: List<DbRow>
)

object ModelLoader {

    fun load(context: Context, zone: Zone): LoadedModel? {
        val zoneDir = File(context.filesDir, "zones/${zone.id}")
        val metaFile = findFirst(
            listOf(
                File(zoneDir, "model/metadata.json"),
                File(zoneDir, "model/metadata_resnet50.json"),
                File(zoneDir, "model/metadata_mobilenetv3.json"),
                File(zoneDir, "model/metadata_mobilenetv3small.json")
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
                File(zoneDir, "model/weights_resnet50.onnx"),
                File(zoneDir, "model/weights_mobilenetv3.onnx"),
                File(zoneDir, "model/weights_mobilenetv3small.onnx")
            )
        )

        val session = if (meta.engine.equals("onnx", ignoreCase = true) && weightsFile != null) {
            runCatching {
                OrtEnvironment.getEnvironment().createSession(weightsFile.absolutePath, OrtSession.SessionOptions())
            }.getOrElse {
                Log.e(TAG, "Failed to load ONNX session", it)
                null
            }
        } else {
            Log.w(TAG, "Unsupported engine ${meta.engine} or weights missing")
            null
        }

        val (db, rows) = loadDb(zoneDir, meta)

        return LoadedModel(meta, session, db, rows)
    }

    private fun findFirst(files: List<File>): File? = files.firstOrNull { it.exists() }

    private fun loadDb(zoneDir: File, meta: ModelInfo): Pair<FloatBuffer?, List<DbRow>> {
        val dbInfo = meta.database ?: return null to emptyList()
        val dbFile = File(zoneDir, "model/${dbInfo.file}")
        val idxFile = File(zoneDir, "model/${dbInfo.index}")
        if (!dbFile.exists() || !idxFile.exists()) return null to emptyList()

        return runCatching {
            FileInputStream(dbFile).use { fis ->
                val channel: FileChannel = fis.channel
                val mapped: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                mapped.order(ByteOrder.LITTLE_ENDIAN)
                val fb: FloatBuffer = mapped.asFloatBuffer()

                val type = object : TypeToken<List<DbRow>>() {}.type
                val rows: List<DbRow> = Gson().fromJson(idxFile.readText(), type)
                fb to rows
            }
        }.getOrElse {
            Log.e(TAG, "Failed to load DB", it)
            null to emptyList()
        }
    }

    private fun halfToFloat(h: Short): Float {
        val s = (h.toInt() shr 15) and 0x00000001
        val e = (h.toInt() shr 10) and 0x0000001F
        val f = h.toInt() and 0x000003FF
        val outE: Int
        val outF: Int
        if (e == 0) {
            outE = 0
            outF = f shl 13
        } else if (e == 31) {
            outE = 255
            outF = f shl 13
        } else {
            outE = e + (127 - 15)
            outF = f shl 13
        }
        val bits = (s shl 31) or (outE shl 23) or outF
        return Float.fromBits(bits)
    }
}

class OnnxLocalizationModel(private val loaded: LoadedModel) : LocalizationModel {
    private val session = loaded.session
    private val info = loaded.info
    private val db = loaded.db        // FloatBuffer?
    private val rows = loaded.dbRows
    private val dim = if (info.descriptorDim > 0) info.descriptorDim
    else db?.let { it.capacity() / (rows.size.coerceAtLeast(1)) } ?: 0
    override suspend fun predict(frames: List<android.graphics.Bitmap>, zone: Zone): PredictionResult {
        if (session == null) {
            Log.w("Localization", "Fallback: session is null")
            return fallback(zone)
        }
        val subset = frames.take(4)
        Log.d("Localization", "Running encoder on ${subset.size} frames, layout=${info.inputLayout}, dim=$dim")
        val descriptors = subset.mapNotNull { runEncoder(it) }
        Log.d("Localization", "Descriptors produced: ${descriptors.size}")
        if (descriptors.isEmpty()) {
            Log.w("Localization", "Fallback: no descriptors from frames")
            return fallback(zone)
        }
        val query = averageAndNormalize(descriptors)

        if (db == null || rows.isEmpty() || dim == 0) {
            Log.w("Localization", "Fallback: db null/empty or dim invalid (dim=$dim, rows=${rows.size})")
            return fallback(zone)
        }
        val (bestIdx, bestSim) = top1Cosine(query, db, dim)
        val row = rows.getOrNull(bestIdx)
        if (row == null) {
            Log.w("Localization", "Fallback: no row for bestIdx=$bestIdx")
            return fallback(zone)
        }
        return PredictionResult(row.lat, row.lon, bestSim.toDouble())
    }

    private fun runEncoder(bmp: android.graphics.Bitmap): FloatArray? {
        val layout = info.inputLayout?.ifBlank { "nhwc" } ?: "nhwc"
        val input = preprocessFrame(
            bmp,
            inputSize = info.inputSize,
            mean = info.mean.toFloatArray(),
            std = info.std.toFloatArray(),
            centerCrop = info.centerCrop,
            inputLayout = layout
        )

        val shape = when (layout.lowercase()) {
            "nchw" -> longArrayOf(1, 3, info.inputSize.toLong(), info.inputSize.toLong())
            else -> longArrayOf(1, info.inputSize.toLong(), info.inputSize.toLong(), 3)
        }

        return runCatching {
            val fb: FloatBuffer = FloatBuffer.wrap(input)
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), fb, shape).use { tensor ->
                session!!.run(mapOf(session.inputNames.first() to tensor)).use { results ->
                    results.forEachIndexed { idx, res ->
                        Log.d("Localization", "Output[$idx]: type=${res.value.javaClass}, name=${session.outputInfo.keys.elementAt(idx)}")
                    }
                    val value = results.first().value
                    when (value) {
                        is OnnxTensor -> {
                            val fbOut = value.floatBuffer
                            val outArr = FloatArray(fbOut.remaining())
                            fbOut.get(outArr)
                            Log.d("Localization", "OnnxTensor -> FloatArray size=${outArr.size}, norm=${l2Norm(outArr)}")
                            l2Normalize(outArr)
                        }
                        is FloatArray -> {
                            Log.d("Localization", "Output FloatArray size=${value.size}, norm=${l2Norm(value)}")
                            l2Normalize(value)
                        }
                        is Array<*> -> {
                            val first = value.firstOrNull()
                            when (first) {
                                is FloatArray -> {
                                    Log.d("Localization", "Output Array<*> -> FloatArray size=${first.size}, norm=${l2Norm(first)}")
                                    l2Normalize(first)
                                }
                                is Array<*> -> (first as? Array<*>)?.firstOrNull()?.let { arr ->
                                    (arr as? FloatArray)?.let {
                                        Log.d("Localization", "Output nested Array -> FloatArray size=${it.size}, norm=${l2Norm(it)}")
                                        l2Normalize(it)
                                    }
                                }
                                else -> {
                                    Log.w("Localization", "Output Array<*> unsupported element type: ${first?.javaClass}")
                                    null
                                }
                            }
                        }
                        else -> {
                            Log.w("Localization", "Unsupported output type: ${value.javaClass}")
                            null
                        }
                    }
                }
            }
        }.onFailure { Log.e("Localization", "Encoder failed", it) }.getOrNull()
    }

    private fun l2Norm(vec: FloatArray): Float {
        var sum = 0f
        for (v in vec) sum += v * v
        return sqrt(sum)
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        val n = l2Norm(vec)
        val inv = if (n > 0f) 1f / n else 1f
        return FloatArray(vec.size) { i -> vec[i] * inv }
    }

    private fun averageAndNormalize(vectors: List<FloatArray>): FloatArray {
        val out = FloatArray(vectors.first().size)
        for (v in vectors) for (i in v.indices) out[i] += v[i]
        val invN = 1f / vectors.size
        for (i in out.indices) out[i] *= invN
        return l2Normalize(out)
    }

    private fun top1Cosine(query: FloatArray, db: FloatBuffer, dim: Int): Pair<Int, Float> {
        var bestIdx = -1
        var best = -1f
        val rowsCount = db.capacity() / dim
        var offset = 0
        for (r in 0 until rowsCount) {
            var dot = 0f
            // absolute gets, no position changes
            for (i in 0 until dim) {
                dot += query[i] * db.get(offset + i)
            }
            if (dot > best) {
                best = dot
                bestIdx = r
            }
            offset += dim
        }
        return bestIdx to best
    }

    private fun fallback(zone: Zone): PredictionResult {
        val center = zone.center
        val lat = center?.lat ?: 0.0
        val lon = center?.lon ?: 0.0
        return PredictionResult(lat, lon, 0.0)
    }
}
