package com.robocam.onboardcamera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStatusViewModelTest {
    @Test
    fun recordingFrameUpdatesMetadataAndCount() {
        val viewModel = CameraStatusViewModel()

        viewModel.setPermission(true)
        viewModel.setCameraState(CameraState.RUNNING)
        viewModel.recordFrame(1920, 1080, 90, 1_000_000_000L)
        viewModel.recordFrame(1920, 1080, 90, 1_033_333_333L)

        val status = viewModel.status.value
        assertEquals(true, status.permissionGranted)
        assertEquals(CameraState.RUNNING, status.cameraState)
        assertEquals(2, status.framesReceived)
        assertEquals(30.0, status.analysisFps, 0.1)
        assertEquals("1920x1080, rotation 90°", status.lastFrame)
    }

    @Test
    fun recordingLifecycleStoresCompletedFileMetadata() {
        val viewModel = CameraStatusViewModel()

        viewModel.setRecordingState(RecordingUiState.STARTING)
        assertEquals(RecordingUiState.STARTING, viewModel.status.value.recording)

        viewModel.setRecordingState(RecordingUiState.PAUSED, durationMs = 2_500)
        assertEquals(2_500, viewModel.status.value.recordingDurationMs)

        viewModel.setRecordingState(
            RecordingUiState.COMPLETED,
            durationMs = 4_000,
            sizeBytes = 12_345,
            uri = "content://media/external/video/1",
        )

        val status = viewModel.status.value
        assertEquals(RecordingUiState.COMPLETED, status.recording)
        assertEquals(12_345, status.recordingSizeBytes)
        assertEquals("content://media/external/video/1", status.recordingUri)
    }

    @Test
    fun recordingErrorsAreVisible() {
        val viewModel = CameraStatusViewModel()

        viewModel.setRecordingState(
            RecordingUiState.ERROR,
            error = "Recording stopped: insufficient storage",
        )

        assertEquals(RecordingUiState.ERROR, viewModel.status.value.recording)
        assertEquals(
            "Recording stopped: insufficient storage",
            viewModel.status.value.recordingError,
        )
    }
}
