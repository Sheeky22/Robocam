package com.robocam.onboardcamera.camera

data class FrameMetadata(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampNanos: Long,
)
