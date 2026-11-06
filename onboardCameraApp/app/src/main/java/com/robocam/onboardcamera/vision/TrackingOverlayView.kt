package com.robocam.onboardcamera.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class TrackingOverlayView(context: Context) : View(context) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        setShadowLayer(5f, 1f, 1f, Color.BLACK)
    }
    private var output: TrackingOutput? = null

    fun update(next: TrackingOutput) {
        output = next
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val state = output ?: return
        val centerX = width / 2f
        canvas.drawLine(centerX, 0f, centerX, height.toFloat(), textPaint)
        state.people.forEach { person ->
            val selected = person.trackId == state.targetId
            boxPaint.color = if (selected) Color.RED else Color.GREEN
            val box = person.box
            val corners = listOf(
                PreviewCoordinateMapper.frameToView(box.left, box.top, width, height, state.frameWidth, state.frameHeight, state.rotationDegrees),
                PreviewCoordinateMapper.frameToView(box.right, box.top, width, height, state.frameWidth, state.frameHeight, state.rotationDegrees),
                PreviewCoordinateMapper.frameToView(box.left, box.bottom, width, height, state.frameWidth, state.frameHeight, state.rotationDegrees),
                PreviewCoordinateMapper.frameToView(box.right, box.bottom, width, height, state.frameWidth, state.frameHeight, state.rotationDegrees),
            ).filterNotNull()
            if (corners.size == 4) {
                val left = corners.minOf { it.x }
                val top = corners.minOf { it.y }
                val right = corners.maxOf { it.x }
                val bottom = corners.maxOf { it.y }
                canvas.drawRect(left, top, right, bottom, boxPaint)
                canvas.drawText("ID ${person.trackId}", left, top - 8f, textPaint)
            }
        }
        val lines = listOf(
            "People: ${state.people.size}",
            "Target: ${state.targetId ?: "none"}",
            "X: ${state.horizontalError?.let { "%.3f".format(it) } ?: "N/A"}",
            "Scale: ${state.scaleError?.let { "%.3f".format(it) } ?: "N/A"}",
            "Rotation: ${state.rotation}",
            "Distance: ${state.distance}",
            "Inference: ${"%.1f".format(state.inferenceMs)} ms",
            "Processing FPS: ${"%.1f".format(state.processingFps)}",
        )
        lines.forEachIndexed { index, line -> canvas.drawText(line, 20f, 40f + index * 36f, textPaint) }
    }
}
