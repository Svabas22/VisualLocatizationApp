package com.example.visuallocatizationapp.model

import android.graphics.Bitmap
import kotlin.math.roundToInt

fun preprocessFrame(
    bitmap: Bitmap,
    inputSize: Int,
    mean: FloatArray,
    std: FloatArray,
    centerCrop: Boolean = true,
    inputLayout: String = "nhwc"
): FloatArray {
    val resized = if (centerCrop) centerCropAndResize(bitmap, inputSize) else {
        Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
    }

    val out = if (inputLayout.lowercase() == "nchw") {
        toCHW(resized, mean, std)
    } else {
        toHWC(resized, mean, std)
    }
    if (resized !== bitmap) resized.recycle()
    return out
}

private fun toHWC(bmp: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
    val chw = FloatArray(3 * bmp.width * bmp.height)
    var idx = 0
    for (y in 0 until bmp.height) {
        for (x in 0 until bmp.width) {
            val pixel = bmp.getPixel(x, y)
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            chw[idx] = (r - mean[0]) / std[0]; idx++
            chw[idx] = (g - mean[1]) / std[1]; idx++
            chw[idx] = (b - mean[2]) / std[2]; idx++
        }
    }
    return chw
}

private fun toCHW(bmp: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
    val out = FloatArray(3 * bmp.width * bmp.height)
    val plane = bmp.width * bmp.height
    for (y in 0 until bmp.height) {
        for (x in 0 until bmp.width) {
            val pixel = bmp.getPixel(x, y)
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            val base = y * bmp.width + x
            out[base] = (r - mean[0]) / std[0]
            out[base + plane] = (g - mean[1]) / std[1]
            out[base + plane * 2] = (b - mean[2]) / std[2]
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
