package com.robocam.onboardcamera.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YoloOnnxDetector(
    modelBytes: ByteArray,
    private val inputSize: Int = 320,
    private val confidenceThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
) : AutoCloseable {
    data class Result(
        val detections: List<PersonDetection>,
        val preprocessingMs: Double,
        val inferenceMs: Double,
        val postprocessingMs: Double,
    )
    private val environment = OrtEnvironment.getEnvironment()
    private val session = environment.createSession(modelBytes, OrtSession.SessionOptions())

    fun detect(bitmap: Bitmap): List<PersonDetection> {
        return detectDetailed(bitmap).detections
    }

    fun detectDetailed(bitmap: Bitmap): Result {
        val preprocessStart = System.nanoTime()
        val input = FloatArray(1 * 3 * inputSize * inputSize)
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        var index = 0
        for (channel in 0..2) {
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val pixel = resized.getPixel(x, y)
                    val value = when (channel) {
                        0 -> android.graphics.Color.red(pixel)
                        1 -> android.graphics.Color.green(pixel)
                        else -> android.graphics.Color.blue(pixel)
                    }
                    input[index++] = value / 255f
                }
            }
        }
        val preprocessingMs = elapsedMs(preprocessStart)

        val inferenceStart = System.nanoTime()
        var rows: Array<FloatArray>
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val output = result[0].value as Array<*>
                @Suppress("UNCHECKED_CAST")
                rows = output[0] as Array<FloatArray>
            }
        }
        val inferenceMs = elapsedMs(inferenceStart)
        val postprocessStart = System.nanoTime()
        val detections = decode(rows, bitmap.width, bitmap.height)
        if (resized !== bitmap && !resized.isRecycled) resized.recycle()
        return Result(detections, preprocessingMs, inferenceMs, elapsedMs(postprocessStart))
    }

    private fun elapsedMs(startNs: Long): Double =
        (System.nanoTime() - startNs) / 1_000_000.0

    private fun decode(rows: Array<FloatArray>, width: Int, height: Int): List<PersonDetection> {
        val detections = mutableListOf<PersonDetection>()
        val candidates = if (rows.size == 84) transpose(rows) else rows
        for (row in candidates) {
            if (row.size < 6) continue
            val classConfidence = row.drop(4).maxOrNull() ?: 0f
            val classId = row.drop(4).indexOfFirst { it == classConfidence }
            val confidence = classConfidence
            if (classId != 0 || confidence < confidenceThreshold) continue
            val scaleX = width.toFloat() / inputSize
            val scaleY = height.toFloat() / inputSize
            val box = BoundingBox(
                left = max(0f, (row[0] - row[2] / 2f) * scaleX),
                top = max(0f, (row[1] - row[3] / 2f) * scaleY),
                right = min(width.toFloat(), (row[0] + row[2] / 2f) * scaleX),
                bottom = min(height.toFloat(), (row[1] + row[3] / 2f) * scaleY),
            )
            detections += PersonDetection(box, confidence)
        }
        return nonMaximumSuppression(detections)
    }

    private fun transpose(rows: Array<FloatArray>): Array<FloatArray> =
        Array(rows[0].size) { column -> FloatArray(rows.size) { row -> rows[row][column] } }

    private fun nonMaximumSuppression(input: List<PersonDetection>): List<PersonDetection> {
        val remaining = input.sortedByDescending { it.confidence }.toMutableList()
        val output = mutableListOf<PersonDetection>()
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            output += best
            remaining.removeAll { iou(best.box, it.box) >= iouThreshold }
        }
        return output
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val w = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val h = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val intersection = w * h
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union > 0) intersection / union else 0f
    }

    override fun close() {
        session.close()
    }
}
