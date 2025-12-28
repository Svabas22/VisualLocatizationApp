package com.example.visuallocatizationapp.model

import android.graphics.Bitmap
import kotlin.math.roundToInt

fun preprocessFrame(
    bitmap: Bitmap,
    inputSize: Int,
    mean: FloatArray,
    std: FloatArray,
    centerCrop: Boolean = true
): FloatArray {
    val resized = if (centerCrop) centerCropAndResize(bitmap, inputSize) else {
        Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
    }

    val chw = FloatArray(3 * inputSize * inputSize)
    var idx = 0
    for (y in 0 until inputSize) {
        for (x in 0 until inputSize) {
            val pixel = resized.getPixel(x, y)
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            chw[idx] = (r - mean[0]) / std[0]; idx++
            chw[idx] = (g - mean[1]) / std[1]; idx++
            chw[idx] = (b - mean[2]) / std[2]; idx++
        }
    }
    if (resized !== bitmap) resized.recycle()
    return chw
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
