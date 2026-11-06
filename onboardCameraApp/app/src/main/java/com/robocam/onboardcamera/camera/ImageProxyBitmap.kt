package com.robocam.onboardcamera.camera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

fun ImageProxy.toRgbBitmap(): Bitmap {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val yIndex = y * yPlane.rowStride + x * yPlane.pixelStride
            val uvX = x / 2
            val uvY = y / 2
            val uIndex = uvY * uPlane.rowStride + uvX * uPlane.pixelStride
            val vIndex = uvY * vPlane.rowStride + uvX * vPlane.pixelStride
            val yValue = yPlane.buffer.get(yIndex).toInt() and 0xff
            val uValue = (uPlane.buffer.get(uIndex).toInt() and 0xff) - 128
            val vValue = (vPlane.buffer.get(vIndex).toInt() and 0xff) - 128
            val red = max(0, min(255, yValue + (1.402f * vValue).toInt()))
            val green = max(0, min(255, yValue - (0.344f * uValue).toInt() - (0.714f * vValue).toInt()))
            val blue = max(0, min(255, yValue + (1.772f * uValue).toInt()))
            pixels[y * width + x] = Color.rgb(red, green, blue)
        }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}
