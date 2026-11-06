package com.robocam.onboardcamera.camera

import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : CameraController {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lastDurationMs = 0L
    private var recordingPaused = false
    private var recordingStateCallback: ((RecordingState) -> Unit)? = null

    override fun start(
        previewView: PreviewView,
        onFrame: FrameSink,
        onInitialized: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder().build().also {
                    it.targetRotation = targetRotation
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            )
                            .build(),
                    )
                    .setTargetRotation(targetRotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, LatestFrameAnalyzer(onFrame)) }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)
                this.videoCapture = videoCapture

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                    videoCapture,
                )
                onInitialized()
            } catch (error: ExecutionException) {
                onError(error.cause ?: error)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                onError(error)
            } catch (error: IllegalArgumentException) {
                onError(error)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun stop() {
        stopRecording()
        provider?.unbindAll()
        executor.shutdown()
    }

    override fun startRecording(onState: (RecordingState) -> Unit) {
        val capture = videoCapture
        if (capture == null) {
            onState(RecordingState.Error("Camera recording is not initialized"))
            return
        }
        if (recording != null) return

        recordingStateCallback = onState
        lastDurationMs = 0L
        recordingPaused = false
        onState(RecordingState.Starting)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "RoboCam-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RoboCam")
        }
        val outputOptions = androidx.camera.video.MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()
        try {
            recording = capture.output
                .prepareRecording(context, outputOptions)
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    handleRecordingEvent(event)
                }
        } catch (error: RuntimeException) {
            recordingStateCallback = null
            onState(RecordingState.Error(error.message ?: "Unable to start recording"))
        }
    }

    override fun pauseRecording() {
        val activeRecording = recording ?: return
        recordingPaused = true
        activeRecording.pause()
        val current = recordingStateCallback
        if (current != null) {
            current(RecordingState.Paused(lastDurationMs))
        }
    }

    override fun resumeRecording() {
        val activeRecording = recording ?: return
        recordingPaused = false
        activeRecording.resume()
        val current = recordingStateCallback
        if (current != null) {
            current(RecordingState.Recording(lastDurationMs))
        }
    }

    override fun stopRecording() {
        recording?.stop()
    }

    private fun handleRecordingEvent(event: VideoRecordEvent) {
        val callback = recordingStateCallback ?: return
        when (event) {
            is VideoRecordEvent.Start -> {
                if (recordingPaused) {
                    callback(RecordingState.Paused(lastDurationMs))
                } else {
                    callback(RecordingState.Recording(lastDurationMs))
                }
            }
            is VideoRecordEvent.Status -> {
                lastDurationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                if (recordingPaused) {
                    callback(RecordingState.Paused(lastDurationMs))
                } else {
                    callback(RecordingState.Recording(lastDurationMs))
                }
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                recordingPaused = false
                val uri = event.outputResults.outputUri
                recordingStateCallback = null
                if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                    callback(
                        RecordingState.Completed(
                            uri.toString(),
                            event.recordingStats.recordedDurationNanos / 1_000_000,
                            event.recordingStats.numBytesRecorded,
                        ),
                    )
                } else {
                    context.contentResolver.delete(uri, null, null)
                    callback(RecordingState.Error(recordingErrorMessage(event.error)))
                }
            }
        }
    }

    private fun recordingErrorMessage(error: Int): String = when (error) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
            "Recording stopped: insufficient storage"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ->
            "Recording stopped: no valid video data"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
            "Recording stopped because the camera became inactive"
        else -> "Recording failed (error $error)"
    }
}
