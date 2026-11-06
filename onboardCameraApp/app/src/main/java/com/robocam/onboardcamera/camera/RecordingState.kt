package com.robocam.onboardcamera.camera

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Starting : RecordingState
    data class Recording(val durationMs: Long) : RecordingState
    data class Paused(val durationMs: Long) : RecordingState
    data class Completed(
        val uri: String,
        val durationMs: Long,
        val bytes: Long,
    ) : RecordingState
    data class Error(val message: String) : RecordingState
}
