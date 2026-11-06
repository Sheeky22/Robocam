package com.robocam.onboardcamera.camera

import androidx.camera.view.PreviewView

interface CameraController {
    fun start(
        previewView: PreviewView,
        onFrame: FrameSink,
        onInitialized: () -> Unit,
        onError: (Throwable) -> Unit,
    )
    fun stop()

    fun startRecording(onState: (RecordingState) -> Unit)

    fun pauseRecording()

    fun resumeRecording()

    fun stopRecording()
}
