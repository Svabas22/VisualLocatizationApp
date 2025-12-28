package com.example.visuallocatizationapp.model

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Returns float array normalized to CHW or HWC depending on inputLayout.
 */
fun preprocessFrame(
    bitmap: Bitmap,
    inputSize: Int,
    mean: FloatArray,
    std: FloatArray,
    centerCrop: Boolean = true,
    inputLayout: String = "nhwc" // "nhwc" or "nchw"
): FloatArray {
    val resized = if (centerCrop) centerCropAndResize(bitmap, inputSize) else {
        Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
    }

    return when (inputLayout.lowercase()) {
        "nchw" -> toCHW(resized, mean, std)
        else -> toHWC(resized, mean, std)
    }.also { if (resized !== bitmap) resized.recycle() }
}

private fun toHWC(bmp: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
    val size = bmp.width * bmp.height
    val out = FloatArray(size * 3)
    var i = 0
    for (y in 0 until bmp.height) {
        for (x in 0 until bmp.width) {
            val p = bmp.getPixel(x, y)
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            out[i++] = (r - mean[0]) / std[0]
            out[i++] = (g - mean[1]) / std[1]
            out[i++] = (b - mean[2]) / std[2]
        }
    }
    return out
}

private fun toCHW(bmp: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
    val c = 3
    val size = bmp.width * bmp.height
    val out = FloatArray(size * c)
    val plane = bmp.width * bmp.height
    for (y in 0 until bmp.height) {
        for (x in 0 until bmp.width) {
            val p = bmp.getPixel(x, y)
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val idx = y * bmp.width + x
            out[idx] = (r - mean[0]) / std[0]
            out[idx + plane] = (g - mean[1]) / std[1]
            out[idx + plane * 2] = (b - mean[2]) / std[2]
        }
    }
    return out
}

private fun centerCropAndResize(src: Bitmap, size: Int): Bitmap {
    val minSide = minOf(src.width, src.height)
    val x = ((src.width - minSide) / 2f).roundToInt()
    val y = ((src.height - minSide) / 2f).roundToInt()
    val square = Bitmap.createBitmap(src, x, y, minSide, minSide)
    val out = Bitmap.createScaledBitmap(square, size, size, true)
    if (square !== src) square.recycle()
    return out
}
