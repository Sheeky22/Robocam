package com.robocam.onboardcamera.camera

import androidx.camera.core.ImageProxy

fun interface FrameSink {
    fun onFrame(image: ImageProxy, metadata: FrameMetadata)
}
