package com.robocam.onboardcamera

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CameraStatus(
    val permissionGranted: Boolean = false,
    val cameraState: CameraState = CameraState.IDLE,
    val framesReceived: Long = 0,
    val framesDropped: Long = 0,
    val analysisFps: Double = 0.0,
    val lastFrame: String = "No frames received",
    val recording: RecordingUiState = RecordingUiState.IDLE,
    val recordingDurationMs: Long = 0,
    val recordingSizeBytes: Long = 0,
    val recordingUri: String? = null,
    val recordingError: String? = null,
)

enum class RecordingUiState {
    IDLE,
    STARTING,
    RECORDING,
    PAUSED,
    COMPLETED,
    ERROR,
}

enum class CameraState {
    IDLE,
    INITIALIZING,
    RUNNING,
    ERROR,
}

class CameraStatusViewModel : ViewModel() {
    private val _status = MutableStateFlow(CameraStatus())
    val status: StateFlow<CameraStatus> = _status.asStateFlow()
    private var previousFrameTimestampNanos: Long? = null

    fun setPermission(granted: Boolean) {
        _status.value = _status.value.copy(permissionGranted = granted)
    }

    fun setCameraState(state: CameraState) {
        _status.value = _status.value.copy(cameraState = state)
    }

    fun setCameraError() {
        setCameraState(CameraState.ERROR)
    }

    fun setRecordingState(
        state: RecordingUiState,
        durationMs: Long = 0,
        sizeBytes: Long = 0,
        uri: String? = null,
        error: String? = null,
    ) {
        _status.value = _status.value.copy(
            recording = state,
            recordingDurationMs = durationMs,
            recordingSizeBytes = sizeBytes,
            recordingUri = uri,
            recordingError = error,
        )
    }

    fun recordFrame(
        width: Int,
        height: Int,
        rotation: Int,
        timestampNanos: Long,
    ) {
        val current = _status.value
        val previousTimestamp = previousFrameTimestampNanos
        val frameIntervalNanos = previousTimestamp?.let { timestampNanos - it } ?: 0L
        previousFrameTimestampNanos = timestampNanos
        val fps = if (frameIntervalNanos > 0) {
            1_000_000_000.0 / frameIntervalNanos
        } else {
            0.0
        }
        _status.value = current.copy(
            framesReceived = current.framesReceived + 1,
            analysisFps = fps,
            lastFrame = "${width}x$height, rotation ${rotation}°",
        )
    }
}
