package com.robocam.onboardcamera.vision

import com.robocam.onboardcamera.camera.FrameMetadata

interface VisionAnalyzer {
    fun analyze(metadata: FrameMetadata)
}
