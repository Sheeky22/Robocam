package com.robocam.onboardcamera.vision

import kotlin.math.max

data class FramePoint(val x: Float, val y: Float)

object PreviewCoordinateMapper {
    fun viewToFrame(
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
    ): FramePoint? {
        if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
        val rotatedWidth = if (rotationDegrees % 180 == 0) frameWidth else frameHeight
        val rotatedHeight = if (rotationDegrees % 180 == 0) frameHeight else frameWidth
        val scale = max(
            viewWidth.toFloat() / rotatedWidth,
            viewHeight.toFloat() / rotatedHeight,
        )
        val displayedWidth = rotatedWidth * scale
        val displayedHeight = rotatedHeight * scale
        val displayX = (viewX - (viewWidth - displayedWidth) / 2f) / scale
        val displayY = (viewY - (viewHeight - displayedHeight) / 2f) / scale
        if (displayX !in 0f..rotatedWidth.toFloat() || displayY !in 0f..rotatedHeight.toFloat()) return null
        return when ((rotationDegrees % 360 + 360) % 360) {
            90 -> FramePoint(displayY, frameHeight - displayX)
            180 -> FramePoint(frameWidth - displayX, frameHeight - displayY)
            270 -> FramePoint(frameWidth - displayY, displayX)
            else -> FramePoint(displayX, displayY)
        }
    }

    fun frameToView(
        frameX: Float,
        frameY: Float,
        viewWidth: Int,
        viewHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
    ): FramePoint? {
        if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
        val rotatedWidth = if (rotationDegrees % 180 == 0) frameWidth else frameHeight
        val rotatedHeight = if (rotationDegrees % 180 == 0) frameHeight else frameWidth
        val displayPoint = when ((rotationDegrees % 360 + 360) % 360) {
            90 -> FramePoint(frameHeight - frameY, frameX)
            180 -> FramePoint(frameWidth - frameX, frameHeight - frameY)
            270 -> FramePoint(frameY, frameWidth - frameX)
            else -> FramePoint(frameX, frameY)
        }
        val scale = max(
            viewWidth.toFloat() / rotatedWidth,
            viewHeight.toFloat() / rotatedHeight,
        )
        return FramePoint(
            displayPoint.x * scale + (viewWidth - rotatedWidth * scale) / 2f,
            displayPoint.y * scale + (viewHeight - rotatedHeight * scale) / 2f,
        )
    }
}
