package com.robocam.onboardcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.os.Handler
import android.os.Looper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.robocam.onboardcamera.camera.CameraXController
import com.robocam.onboardcamera.camera.FrameMetadata
import com.robocam.onboardcamera.camera.RecordingState
import com.robocam.onboardcamera.RecordingUiState
import com.robocam.onboardcamera.camera.toRgbBitmap
import com.robocam.onboardcamera.protocol.Esp32ConnectionState
import com.robocam.onboardcamera.protocol.Esp32Message
import com.robocam.onboardcamera.protocol.Esp32WebSocketClient
import com.robocam.onboardcamera.vision.DistanceDecision
import com.robocam.onboardcamera.vision.PreviewCoordinateMapper
import com.robocam.onboardcamera.vision.RotationDecision
import com.robocam.onboardcamera.vision.ByteTrack
import com.robocam.onboardcamera.vision.TrackingController
import com.robocam.onboardcamera.vision.TrackingOverlayView
import com.robocam.onboardcamera.vision.TrackingOutput
import com.robocam.onboardcamera.vision.YoloOnnxDetector
import com.robocam.onboardcamera.webrtc.AndroidWebRtcClient
import com.robocam.onboardcamera.service.RoboCamForegroundService
import com.robocam.onboardcamera.performance.VisionPerformance
import com.robocam.onboardcamera.performance.PerformanceSnapshot
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "RoboCamCamera"
        const val AUTONOMOUS_DRIVE_PERCENT = 100
        const val TARGET_RECOVERY_PERCENT = 20
        const val TARGET_RECOVERY_WINDOW_MS = 1_000L
        const val AUTONOMOUS_PULSE_MS = 1000L
        const val REMOTE_OPERATOR_MODE = true
    }

    private fun updateStatusText(status: CameraStatus = viewModel.status.value) {
        statusText.text = buildString {
            appendLine("RoboCam onboard camera")
            appendLine("Permission: ${if (status.permissionGranted) "granted" else "required"}")
            appendLine("Camera: ${status.cameraState.name.lowercase()}")
            appendLine("Frames observed: ${status.framesReceived}")
            appendLine("Analysis FPS: ${"%.1f".format(status.analysisFps)}")
            appendLine("Recording: ${status.recording.name.lowercase()} ${formatDuration(status.recordingDurationMs)}")
            status.recordingError?.let { appendLine("Recording error: $it") }
            appendLine("ESP32: ${esp32State.name.lowercase()} | AUTO: ${if (autonomousEnabled) "active" else "off"}")
            appendLine("Manual control: ${if (manualControlEnabled) "ready" else "off"}")
            appendLine("Target: ${latestOutput?.targetId ?: "none"} | Decision: ${latestOutput?.rotation ?: RotationDecision.STOP}/${latestOutput?.distance ?: DistanceDecision.STOP}")
            appendLine("Drive: L${lastCommand.first} R${lastCommand.second} seq ${lastCommandSequence ?: "none"}")
            appendLine("Last ack: $lastAcknowledgement")
            appendLine("Safety: $safetyReason")
            append("Latest: ${status.lastFrame}")
        }
    }

    private fun toggleEsp32Connection() {
        if (esp32State == Esp32ConnectionState.DISCONNECTED || esp32State == Esp32ConnectionState.ERROR) {
            ensureEsp32Client()
        } else {
            stopAutonomousMode("ESP32 disconnected by user")
            esp32Client?.close()
        }
    }

    private fun requestAutonomousMode() {
        val output = latestOutput
        if (esp32State != Esp32ConnectionState.REGISTERED) {
            safetyReason = "Connect and register ESP32 first"
            return
        }
        if (output?.targetId == null || !output.targetDetected) {
            safetyReason = "Select a detected target first"
            return
        }
        if (esp32Client?.sendAutoRequest() == true) {
            safetyReason = "Waiting for ESP32 AUTO acknowledgement"
        } else {
            safetyReason = "AUTO request could not be sent"
        }
    }

    private fun requestManualMode() {
        if (esp32State != Esp32ConnectionState.REGISTERED) {
            safetyReason = "Connect and register ESP32 first"
        } else if (esp32Client?.sendManualRequest() == true) {
            safetyReason = "Waiting for ESP32 MANUAL acknowledgement"
        } else {
            safetyReason = "MANUAL request could not be sent"
        }
        updateStatusText()
    }

    private fun sendManualDrive(left: Int, right: Int) {
        if (!manualControlEnabled || esp32State != Esp32ConnectionState.REGISTERED) return
        val now = System.currentTimeMillis()
        if (left to right != lastCommand || now - lastCommandAtMs >= 200L) {
            lastCommandSequence = esp32Client?.sendDrive(left, right)
            lastCommand = left to right
            lastCommandAtMs = now
            updateStatusText()
        }
    }

    private fun stopManualDrive() {
        manualDriveHandler.removeCallbacks(manualDriveHeartbeat)
        if (manualControlEnabled && esp32State == Esp32ConnectionState.REGISTERED) {
            sendManualDrive(0, 0)
        }
    }

    private fun startManualDrive(left: Int, right: Int) {
        if (!manualControlEnabled || esp32State != Esp32ConnectionState.REGISTERED) return
        manualDriveHandler.removeCallbacks(manualDriveHeartbeat)
        manualDriveLeft = left
        manualDriveRight = right
        sendManualDrive(left, right)
        manualDriveHandler.post(manualDriveHeartbeat)
    }

    private fun stopAutonomousMode(reason: String) {
        autonomousEnabled = false
        safetyReason = reason
        autonomousPulseHandler.removeCallbacks(autonomousPulseStop)
        esp32Client?.sendStop()
        esp32Client?.sendDrive(0, 0)
    }

    private fun emergencyStop() {
        autonomousEnabled = false
        safetyReason = "Emergency stop requested"
        esp32Client?.sendEmergencyStop()
    }

    private fun handleEsp32Message(message: Esp32Message) {
        if (message.type == "webrtc_offer_request") {
            Log.d(TAG, "WebRTC offer request received for session=${message.sessionId}")
            message.sessionId?.let { webRtcClient?.bindSession(it) }
            return
        }
        if (message.type == "camera" || message.type == "target_select" || message.type == "phone_command") {
            handleRemoteCommand(message)
            return
        }
        if (message.type == "webrtc_signal" &&
            message.sessionId != null &&
            message.signalType != null &&
            message.payload != null
        ) {
            webRtcClient?.handleSignal(message.sessionId, message.signalType, message.payload)
            return
        }
        if (message.type == "ack") {
            lastAcknowledgement = "${message.command}: ${if (message.accepted == true) "accepted" else "rejected"}"
            if (message.command == "control_mode" && message.mode == null && message.accepted == true) {
                autonomousEnabled = true
                safetyReason = "AUTO enabled by ESP32"
            }
            if (message.accepted == false) safetyReason = message.error ?: "ESP32 rejected command"
        }
        if (message.type == "control_mode" && message.mode == "AUTO") {
            manualControlEnabled = false
            autonomousEnabled = true
            safetyReason = "AUTO enabled by ESP32"
        } else if (message.type == "control_mode" && message.mode == "MANUAL") {
            autonomousEnabled = false
            manualControlEnabled = true
            safetyReason = "MANUAL enabled by ESP32"
        } else if (message.type == "control_mode" && message.mode != null) {
            manualControlEnabled = false
            autonomousEnabled = false
            safetyReason = "Manual/STOPPED override received from ESP32"
        }
        if (message.type == "motor_status" && message.mode != "AUTO") {
            autonomousEnabled = false
        }
        if (message.type == "error") safetyReason = message.error ?: "ESP32 error"
        updateStatusText()
    }

    private fun handleRemoteCommand(message: Esp32Message) {
        when (message.type to message.action) {
            "camera" to "START_RECORD" -> startRecording()
            "camera" to "PAUSE_RECORD" -> if (recordingState is RecordingState.Recording) cameraController?.pauseRecording()
                else sendRecordingCommandError("Recording is not active")
            "camera" to "RESUME_RECORD" -> if (recordingState is RecordingState.Paused) cameraController?.resumeRecording()
                else sendRecordingCommandError("Recording is not paused")
            "camera" to "STOP_RECORD" -> if (recordingState is RecordingState.Recording || recordingState is RecordingState.Paused) cameraController?.stopRecording()
                else sendRecordingCommandError("There is no recording to stop")
            "target_select" to null -> {
                val output = latestOutput
                val x = message.x
                val y = message.y
                if (output != null && x != null && y != null) {
                    synchronized(visionLock) {
                        trackingController.selectAtNormalizedCoordinates(
                            x,
                            y,
                            output.frameWidth,
                            output.frameHeight,
                            output.people,
                        )
                    }
                    safetyReason = "Target selected remotely; AUTO requires explicit start"
                }
            }
            "phone_command" to "START_AUTO" -> requestAutonomousMode()
            "phone_command" to "STOP_AUTO" -> stopAutonomousMode("Remote AUTO stop")
            "phone_command" to "EMERGENCY_STOP" -> emergencyStop()
        }
        updateStatusText()
    }

    private fun sendAutonomousDecision(output: TrackingOutput) {
        if (!autonomousEnabled || !visionHealthy || esp32State != Esp32ConnectionState.REGISTERED) return
        val now = System.currentTimeMillis()
        val command = if (!output.targetDetected || output.targetId == null) {
            val lostAt = targetLostAtMs ?: now.also { targetLostAtMs = it }
            if (lastRotationDecision != RotationDecision.STOP &&
                now - lostAt <= TARGET_RECOVERY_WINDOW_MS
            ) {
                safetyReason = "Target lost; slow reacquisition"
                when (lastRotationDecision) {
                    RotationDecision.LEFT -> -TARGET_RECOVERY_PERCENT to TARGET_RECOVERY_PERCENT
                    RotationDecision.RIGHT -> TARGET_RECOVERY_PERCENT to -TARGET_RECOVERY_PERCENT
                    RotationDecision.STOP -> 0 to 0
                }
            } else {
                autonomousEnabled = false
                safetyReason = "Target lost; motors stopped"
                0 to 0
            }
        } else {
            targetLostAtMs = null
            lastRotationDecision = output.rotation
            when {
                output.rotation == RotationDecision.LEFT ->
                    -AUTONOMOUS_DRIVE_PERCENT to AUTONOMOUS_DRIVE_PERCENT
                output.rotation == RotationDecision.RIGHT ->
                    AUTONOMOUS_DRIVE_PERCENT to -AUTONOMOUS_DRIVE_PERCENT
                output.distance == DistanceDecision.FORWARD ->
                    AUTONOMOUS_DRIVE_PERCENT to AUTONOMOUS_DRIVE_PERCENT
                output.distance == DistanceDecision.BACKWARD ->
                    -AUTONOMOUS_DRIVE_PERCENT to -AUTONOMOUS_DRIVE_PERCENT
                else ->
                    0 to 0
            }
        }
        if (command != lastCommand || now - lastCommandAtMs >= 200L) {
            autonomousPulseHandler.removeCallbacks(autonomousPulseStop)
            lastCommandSequence = esp32Client?.sendDrive(command.first, command.second)
            lastCommand = command
            lastCommandAtMs = now
            if (command.first != 0 || command.second != 0) {
                autonomousPulseHandler.postDelayed(autonomousPulseStop, AUTONOMOUS_PULSE_MS)
            }
        }
    }

    private val viewModel = CameraStatusViewModel()
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var overlayView: TrackingOverlayView
    private lateinit var performanceText: TextView
    private lateinit var recordingText: TextView
    private var cameraController: CameraXController? = null
    private var latestOutput: TrackingOutput? = null
    private val tracker = ByteTrack()
    private val trackingController = TrackingController()
    private var detector: YoloOnnxDetector? = null
    private val visionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val visionBusy = AtomicBoolean(false)
    private val visionLock = Any()
    private lateinit var performance: VisionPerformance
    private var benchmarkRunning = false
    private var recordingState: RecordingState = RecordingState.Idle
    private var latestMetadata: FrameMetadata? = null
    private var esp32State = Esp32ConnectionState.DISCONNECTED
    private var esp32Client: Esp32WebSocketClient? = null
    private var webRtcClient: AndroidWebRtcClient? = null
    private var autonomousEnabled = false
    private var manualControlEnabled = false
    private var lastAcknowledgement = "none"
    private var safetyReason = "Autonomous tracking disabled"
    private var lastCommandAtMs = 0L
    private var lastCommand = Int.MIN_VALUE to Int.MIN_VALUE
    private var lastCommandSequence: Long? = null
    private var visionHealthy = false
    private var lastRotationDecision = RotationDecision.STOP
    private var targetLostAtMs: Long? = null
    private val manualDriveHandler = Handler(Looper.getMainLooper())
    private val autonomousPulseHandler = Handler(Looper.getMainLooper())
    private val autonomousPulseStop = Runnable { esp32Client?.sendDrive(0, 0) }
    private var manualDriveLeft = 0
    private var manualDriveRight = 0
    private val manualDriveHeartbeat = object : Runnable {
        override fun run() {
            if (manualDriveLeft != 0 || manualDriveRight != 0) {
                sendManualDrive(manualDriveLeft, manualDriveRight)
                manualDriveHandler.postDelayed(this, 200L)
            }
        }
    }
    private val exportBenchmark = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(VisionPerformance.CSV_HEADER)
                writer.write(performance.csvRow())
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setPermission(granted)
        if (granted) startCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.startForegroundService(
            this,
            Intent(this, RoboCamForegroundService::class.java),
        )
        performance = VisionPerformance(this)
        setContentView(createContentView())
        observeStatus()

        if (hasCameraPermission()) {
            viewModel.setPermission(true)
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun createContentView(): FrameLayout {
        previewView = PreviewView(this)
        previewView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        statusText = TextView(this).apply {
            setPadding(24, 20, 24, 20)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        performanceText = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        recordingText = TextView(this).apply {
            setPadding(16, 8, 16, 8)
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            text = "Recording: idle"
        }
        overlayView = TrackingOverlayView(this)
        return FrameLayout(this).apply {
            addView(previewView)
            addView(overlayView)
            addView(
                performanceText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )
            addView(
                recordingText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ).apply { topMargin = 104 },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    visibility = if (REMOTE_OPERATOR_MODE) View.GONE else View.VISIBLE
                    addView(Button(this@MainActivity).apply {
                        text = "Connect ESP32"
                        setOnClickListener { toggleEsp32Connection() }
                    })
                    addView(Button(this@MainActivity).apply {
                        text = "Enable MANUAL"
                        setOnClickListener { requestManualMode() }
                    })
                    addView(Button(this@MainActivity).apply {
                        text = "Start AUTO"
                        setOnClickListener { requestAutonomousMode() }
                    })
                    addView(Button(this@MainActivity).apply {
                        text = "Stop AUTO"
                        setOnClickListener { stopAutonomousMode("User stopped autonomous tracking") }
                    })
                    addView(Button(this@MainActivity).apply {
                        text = "EMERGENCY"
                        setOnClickListener { emergencyStop() }
                    })
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ),
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (REMOTE_OPERATOR_MODE) View.GONE else View.VISIBLE
                    gravity = Gravity.CENTER
                    addView(manualDriveButton("↑", 100, 100))
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            gravity = Gravity.CENTER
                            addView(manualDriveButton("←", -100, 100))
                            addView(manualDriveButton("STOP", 0, 0))
                            addView(manualDriveButton("→", 100, -100))
                        },
                    )
                    addView(manualDriveButton("↓", -100, -100))
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    visibility = if (REMOTE_OPERATOR_MODE) View.GONE else View.VISIBLE
                    addView(Button(this@MainActivity).apply {
                        text = "Start recording"
                        setOnClickListener { startRecording() }
                    })
                    addView(Button(this@MainActivity).apply {
                        text = "Pause / resume"
                        setOnClickListener { toggleRecordingPause() }
                    })
                    addView(Button(this@MainActivity).apply {
                        text = "Stop recording"
                        setOnClickListener { cameraController?.stopRecording() }
                    })
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = 120 },
            )
            addView(
                Button(this@MainActivity).apply {
                    text = "Start Benchmark"
                    visibility = if (REMOTE_OPERATOR_MODE) View.GONE else View.VISIBLE
                    setOnClickListener {
                        benchmarkRunning = true
                        performance.start()
                        updatePerformanceDashboard()
                    }
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                ),
            )
            addView(
                Button(this@MainActivity).apply {
                    text = "Stop / Export CSV"
                    visibility = if (REMOTE_OPERATOR_MODE) View.GONE else View.VISIBLE
                    setOnClickListener {
                        benchmarkRunning = false
                        performance.stop()
                        exportBenchmark.launch("robocam-benchmark.csv")
                    }
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ),
            )
            addView(
                Button(this@MainActivity).apply {
                    text = "Reset Stats"
                    visibility = if (REMOTE_OPERATOR_MODE) View.GONE else View.VISIBLE
                    setOnClickListener {
                        performance.reset()
                        updatePerformanceDashboard()
                    }
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END,
                ),
            )
            addView(
                statusText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
            setOnTouchListener { _, event ->
                if (!REMOTE_OPERATOR_MODE && event.action == MotionEvent.ACTION_DOWN) {
                    latestOutput?.let { output ->
                        val metadata = latestMetadata
                        val point = metadata?.let {
                            PreviewCoordinateMapper.viewToFrame(
                                event.x,
                                event.y,
                                width,
                                height,
                                output.frameWidth,
                                output.frameHeight,
                                it.rotationDegrees,
                            )
                        }
                        if (point != null) synchronized(visionLock) {
                            trackingController.selectAtFrameCoordinates(
                                point.x,
                                point.y,
                                output.frameHeight,
                                output.people,
                            )
                            safetyReason = "Target selected; AUTO requires explicit start"
                        }
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.status.collect { status ->
                    updateStatusText(status)
                }
            }
        }
    }

    private fun startCamera() {
        if (cameraController != null) return
        viewModel.setCameraState(CameraState.INITIALIZING)
        val controller = CameraXController(this, this)
        cameraController = controller
        detector = detector ?: YoloOnnxDetector(assets.open("yolo11n.onnx").use { it.readBytes() })
        ensureEsp32Client()
        webRtcClient = webRtcClient ?: AndroidWebRtcClient(
            context = this,
            sendSignal = { session, signalType, payload ->
                esp32Client?.sendWebRtcSignal(session, signalType, payload) == true
            },
            onState = { state ->
                runOnUiThread {
                    safetyReason = "WebRTC ${state.name.lowercase()}"
                    updateStatusText()
                }
            },
        ).also { it.start() }
        controller.start(
            previewView = previewView,
            onFrame = { image, metadata: FrameMetadata ->
                latestMetadata = metadata
                webRtcClient?.pushFrame(image, metadata)
                performance.onCameraFrame(metadata.timestampNanos)
                if (!visionBusy.compareAndSet(false, true)) {
                    performance.onDroppedFrame()
                    return@start
                }
                val preprocessingStart = System.nanoTime()
                val frame = try {
                    image.toRgbBitmap()
                } catch (error: RuntimeException) {
                    visionBusy.set(false)
                    Log.e(TAG, "Camera frame conversion failed", error)
                    return@start
                }
                performance.recordImageConversion((System.nanoTime() - preprocessingStart) / 1_000_000.0)
                runOnUiThread {
                    viewModel.recordFrame(
                        metadata.width,
                        metadata.height,
                        metadata.rotationDegrees,
                        metadata.timestampNanos,
                    )
                }

                visionExecutor.execute {
                    try {
                        val endToEndStart = System.nanoTime()
                        val inferenceStart = System.nanoTime()
                        val result = detector?.detectDetailed(frame)
                        val detections = result?.detections.orEmpty()
                        performance.recordPreprocessing(result?.preprocessingMs ?: 0.0)
                        performance.recordInference(result?.inferenceMs ?: ((System.nanoTime() - inferenceStart) / 1_000_000.0))
                        performance.recordPostprocessing(result?.postprocessingMs ?: 0.0)
                        val trackStart = System.nanoTime()
                        val people = tracker.update(detections).map {
                            com.robocam.onboardcamera.vision.TrackedPerson(
                                it.box,
                                it.confidence,
                                it.trackId,
                            )
                        }
                        performance.recordByteTrack((System.nanoTime() - trackStart) / 1_000_000.0)
                        val controllerStart = System.nanoTime()
                        val output = synchronized(visionLock) {
                            trackingController.update(
                                people,
                                metadata.width,
                                metadata.height,
                                metadata.timestampNanos,
                                result?.inferenceMs ?: 0.0,
                                metadata.rotationDegrees,
                            )
                        }
                        performance.recordController((System.nanoTime() - controllerStart) / 1_000_000.0)
                        performance.recordEndToEnd((System.nanoTime() - endToEndStart) / 1_000_000.0)
                        performance.onProcessed(
                            metadata.timestampNanos,
                            output.people.size,
                            output.targetId,
                            output.targetDetected,
                        )
                        latestOutput = output
                        visionHealthy = true
                        val peopleJson = output.people.joinToString(
                            prefix = "[",
                            postfix = "]",
                            separator = ",",
                        ) {
                            """{"id":${it.trackId},"left":${it.box.left / output.frameWidth},"top":${it.box.top / output.frameHeight},"right":${it.box.right / output.frameWidth},"bottom":${it.box.bottom / output.frameHeight}}"""
                        }
                        esp32Client?.sendTrackingStatus(
                            output.frameWidth,
                            output.frameHeight,
                            output.targetId,
                            peopleJson,
                        )
                        sendAutonomousDecision(output)
                        runOnUiThread {
                            overlayView.update(output)
                            updatePerformanceDashboard()
                        }
                    } catch (error: RuntimeException) {
                        visionHealthy = false
                        if (autonomousEnabled) stopAutonomousMode("Vision processing failed")
                        Log.e(TAG, "Vision processing failed", error)
                    } finally {
                        if (!frame.isRecycled) frame.recycle()
                        visionBusy.set(false)
                    }
                }
            },
            onInitialized = { viewModel.setCameraState(CameraState.RUNNING) },
            onError = { error ->
                Log.e(TAG, "Camera initialization failed", error)
                viewModel.setCameraError()
            },
        )
    }

    private fun updatePerformanceDashboard() {
        val snapshot = performance.snapshot()
        performanceText.text = formatPerformance(snapshot)
    }

    private fun startRecording() {
        if (recordingState is RecordingState.Recording || recordingState is RecordingState.Paused || recordingState is RecordingState.Starting) {
            sendRecordingCommandError("A recording is already active")
            return
        }
        cameraController?.startRecording { state ->
            runOnUiThread { updateRecordingState(state) }
        } ?: updateRecordingState(RecordingState.Error("Camera is not initialized"))
    }

    private fun sendRecordingCommandError(reason: String) {
        esp32Client?.sendRecordingStatus("error", error = reason)
    }

    private fun toggleRecordingPause() {
        when (recordingState) {
            is RecordingState.Recording -> cameraController?.pauseRecording()
            is RecordingState.Paused -> cameraController?.resumeRecording()
            else -> Unit
        }
    }

    private fun updateRecordingState(state: RecordingState) {
        recordingState = state
        val remoteStatus = when (state) {
            RecordingState.Idle -> "idle"
            RecordingState.Starting -> "starting"
            is RecordingState.Recording -> "recording"
            is RecordingState.Paused -> "paused"
            is RecordingState.Completed -> "stopped"
            is RecordingState.Error -> "error"
        }
        val remoteDuration = when (state) {
            is RecordingState.Recording -> state.durationMs
            is RecordingState.Paused -> state.durationMs
            is RecordingState.Completed -> state.durationMs
            else -> 0L
        }
        esp32Client?.sendRecordingStatus(remoteStatus, remoteDuration)
        when (state) {
            RecordingState.Idle -> viewModel.setRecordingState(RecordingUiState.IDLE)
            RecordingState.Starting -> viewModel.setRecordingState(RecordingUiState.STARTING)
            is RecordingState.Recording -> viewModel.setRecordingState(
                RecordingUiState.RECORDING,
                durationMs = state.durationMs,
            )
            is RecordingState.Paused -> viewModel.setRecordingState(
                RecordingUiState.PAUSED,
                durationMs = state.durationMs,
            )
            is RecordingState.Completed -> {
                viewModel.setRecordingState(
                    RecordingUiState.COMPLETED,
                    durationMs = state.durationMs,
                    sizeBytes = state.bytes,
                    uri = state.uri,
                )
                Log.i(TAG, "Recording saved: ${state.uri}, ${state.bytes} bytes")
            }
            is RecordingState.Error -> viewModel.setRecordingState(
                RecordingUiState.ERROR,
                error = state.message,
            )
        }
        recordingText.text = when (state) {
            RecordingState.Idle -> "Recording: idle"
            RecordingState.Starting -> "Recording: starting"
            is RecordingState.Recording -> "Recording: active ${formatDuration(state.durationMs)}"
            is RecordingState.Paused -> "Recording: paused ${formatDuration(state.durationMs)}"
            is RecordingState.Completed -> "Saved MP4: ${formatDuration(state.durationMs)}, ${state.bytes / 1024} KB"
            is RecordingState.Error -> "Recording error: ${state.message}"
        }
    }

    private fun formatDuration(durationMs: Long): String =
        "%02d:%02d".format(durationMs / 60_000, (durationMs / 1_000) % 60)

    private fun manualDriveButton(label: String, left: Int, right: Int): Button =
        Button(this).apply {
            text = label
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (left == 0 && right == 0) stopManualDrive()
                        else startManualDrive(left, right)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (left != 0 || right != 0) stopManualDrive()
                        true
                    }
                    else -> true
                }
            }
        }

    private fun formatPerformance(s: PerformanceSnapshot): String = buildString {
        appendLine("VISION PERFORMANCE ${if (benchmarkRunning) "[BENCHMARK]" else "[LIVE]"}")
        appendLine("Camera ${"%.1f".format(s.cameraFps)} FPS | AI ${"%.1f".format(s.processingFps)} FPS | " +
            "E2E ${"%.1f".format(s.endToEnd.averageMs)} ms (P50 ${"%.1f".format(s.endToEnd.p50Ms)}, P95 ${"%.1f".format(s.endToEnd.p95Ms)})")
        appendLine("Convert ${"%.1f".format(s.imageConversion.averageMs)} | Pre ${"%.1f".format(s.preprocessing.averageMs)} | YOLO ${"%.1f".format(s.inference.averageMs)} | " +
            "Post ${"%.1f".format(s.postprocessing.averageMs)} | BT ${"%.2f".format(s.byteTrack.averageMs)} | " +
            "Ctrl ${"%.2f".format(s.controller.averageMs)} ms")
        appendLine("Frames recv ${s.receivedFrames} / processed ${s.processedFrames} / dropped ${s.droppedFrames}")
        appendLine("Model ${s.model} ${s.inputResolution} ${s.precision} | ${s.runtime} | ${s.executionProvider}")
        appendLine("Memory ${"%.1f".format(s.memoryMb)} MB | CPU ${s.cpuPercent?.let { "%.1f%%".format(it) } ?: "N/A"} | Thermal ${s.thermalStatus}")
        append("People ${s.people} | Target ${s.targetId ?: "none"} | Losses ${s.targetLossEvents} | Accelerator ${s.acceleratorStatus}")
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        stopManualDrive()
        stopAutonomousMode("Android app closed")
        esp32Client?.close()
        webRtcClient?.stopFramePublishing()
        cameraController?.stop()
        cameraController = null
        webRtcClient?.close()
        webRtcClient = null
        detector?.close()
        detector = null
        visionExecutor.shutdown()
        super.onDestroy()
    }

    private fun ensureEsp32Client() {
        if (esp32Client != null) return
        esp32Client = Esp32WebSocketClient(
            onState = { state ->
                runOnUiThread {
                    esp32State = state
                    if (state != Esp32ConnectionState.REGISTERED) {
                        stopManualDrive()
                        stopAutonomousMode("ESP32 connection lost")
                    }
                    updateStatusText()
                }
            },
            onMessage = { message ->
                runOnUiThread { handleEsp32Message(message) }
            },
        )
        esp32Client?.connect()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission() && cameraController == null) {
            viewModel.setPermission(true)
            startCamera()
        }
        if (recordingState is RecordingState.Paused) {
            cameraController?.resumeRecording()
        }
    }

    override fun onPause() {
        super.onPause()
    }
}
