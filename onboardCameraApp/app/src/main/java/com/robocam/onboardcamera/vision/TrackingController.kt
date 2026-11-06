package com.robocam.onboardcamera.vision

enum class RotationDecision { LEFT, RIGHT, STOP }
enum class DistanceDecision { FORWARD, BACKWARD, STOP }

data class TrackingOutput(
    val targetDetected: Boolean,
    val targetId: Int?,
    val people: List<TrackedPerson>,
    val selectedBox: BoundingBox?,
    val frameWidth: Int,
    val frameHeight: Int,
    val horizontalError: Float?,
    val scaleError: Float?,
    val rotation: RotationDecision,
    val distance: DistanceDecision,
    val inferenceMs: Double,
    val processingFps: Double,
    val rotationDegrees: Int = 0,
)

class TrackingController(
    private val alpha: Float = 0.25f,
    private val horizontalStart: Float = 0.05f,
    private val horizontalStop: Float = 0.025f,
    private val scaleStart: Float = 0.08f,
    private val scaleStop: Float = 0.04f,
) {
    private var selectedId: Int? = null
    private var referenceHeight: Float? = null
    private var smoothX: Float? = null
    private var smoothScale: Float? = null
    private var rotation = RotationDecision.STOP
    private var distance = DistanceDecision.STOP
    private var previousTimestampNanos: Long? = null
    private var processingFps = 0.0

    fun selectAtFrameCoordinates(x: Float, y: Float, frameHeight: Int, people: List<TrackedPerson>) {
        val candidate = people.firstOrNull { it.box.contains(x, y) } ?: return
        if (candidate.box.height <= 0f || frameHeight <= 0) return
        selectedId = candidate.trackId
        referenceHeight = candidate.box.height / frameHeight
        resetMotion()
    }

    fun selectAtNormalizedCoordinates(x: Float, y: Float, frameWidth: Int, frameHeight: Int, people: List<TrackedPerson>) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        selectAtFrameCoordinates(x * frameWidth, y * frameHeight, frameHeight, people)
    }

    fun releaseTarget() {
        selectedId = null
        referenceHeight = null
        resetMotion()
    }

    fun update(
        people: List<TrackedPerson>,
        frameWidth: Int,
        frameHeight: Int,
        timestampNanos: Long,
        inferenceMs: Double,
        rotationDegrees: Int = 0,
    ): TrackingOutput {
        val currentTimestamp = previousTimestampNanos
        if (currentTimestamp != null && timestampNanos > currentTimestamp) {
            val instant = 1_000_000_000.0 / (timestampNanos - currentTimestamp)
            processingFps = if (processingFps == 0.0) instant else 0.9 * processingFps + 0.1 * instant
        }
        previousTimestampNanos = timestampNanos

        val selected = people.firstOrNull { it.trackId == selectedId }
        if (selected == null && selectedId != null) {
            targetLost()
        } else if (selected != null && frameWidth > 0 && frameHeight > 0) {
            val rawX = (selected.box.centerX - frameWidth / 2f) / (frameWidth / 2f)
            val currentHeight = selected.box.height / frameHeight
            val reference = referenceHeight
            if (reference != null && reference > 0f && currentHeight > 0f) {
                val rawScale = currentHeight / reference - 1f
                smoothX = ema(rawX, smoothX)
                smoothScale = ema(rawScale, smoothScale)
                rotation = updateRotation(smoothX ?: 0f)
                distance = updateDistance(smoothScale ?: 0f)
            }
        }

        return TrackingOutput(
            targetDetected = selected != null,
            targetId = selectedId,
            people = people,
            selectedBox = selected?.box,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            horizontalError = smoothX,
            scaleError = smoothScale,
            rotation = rotation,
            distance = distance,
            inferenceMs = inferenceMs,
            processingFps = processingFps,
            rotationDegrees = rotationDegrees,
        )
    }

    private fun ema(raw: Float, previous: Float?): Float =
        if (previous == null) raw else alpha * raw + (1f - alpha) * previous

    private fun resetMotion() {
        smoothX = null
        smoothScale = null
        rotation = RotationDecision.STOP
        distance = DistanceDecision.STOP
    }

    private fun targetLost() = resetMotion()

    private fun updateRotation(error: Float): RotationDecision = when (rotation) {
        RotationDecision.STOP -> when {
            error < -horizontalStart -> RotationDecision.LEFT
            error > horizontalStart -> RotationDecision.RIGHT
            else -> RotationDecision.STOP
        }
        RotationDecision.LEFT -> when {
            error > horizontalStart -> RotationDecision.RIGHT
            error > -horizontalStop -> RotationDecision.STOP
            else -> RotationDecision.LEFT
        }
        RotationDecision.RIGHT -> when {
            error < -horizontalStart -> RotationDecision.LEFT
            error < horizontalStop -> RotationDecision.STOP
            else -> RotationDecision.RIGHT
        }
    }

    private fun updateDistance(error: Float): DistanceDecision = when (distance) {
        DistanceDecision.STOP -> when {
            error < -scaleStart -> DistanceDecision.FORWARD
            error > scaleStart -> DistanceDecision.BACKWARD
            else -> DistanceDecision.STOP
        }
        DistanceDecision.FORWARD -> when {
            error > scaleStart -> DistanceDecision.BACKWARD
            error > -scaleStop -> DistanceDecision.STOP
            else -> DistanceDecision.FORWARD
        }
        DistanceDecision.BACKWARD -> when {
            error < -scaleStart -> DistanceDecision.FORWARD
            error < scaleStop -> DistanceDecision.STOP
            else -> DistanceDecision.BACKWARD
        }
    }
}
