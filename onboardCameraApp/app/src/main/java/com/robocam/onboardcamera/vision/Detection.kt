package com.robocam.onboardcamera.vision

data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom
}

data class PersonDetection(
    val box: BoundingBox,
    val confidence: Float,
    val classId: Int = 0,
    val trackId: Int? = null,
)

data class TrackedPerson(
    val box: BoundingBox,
    val confidence: Float,
    val trackId: Int,
)
