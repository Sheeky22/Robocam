package com.robocam.onboardcamera.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LatestFrameAnalyzer(
    private val sink: FrameSink,
) : ImageAnalysis.Analyzer {
    private val analyzing = AtomicBoolean(false)
    private val framesReceived = AtomicLong(0)
    private val framesDropped = AtomicLong(0)

    val metrics: FrameMetrics
        get() = FrameMetrics(
            framesReceived = framesReceived.get(),
            framesDropped = framesDropped.get(),
        )

    override fun analyze(image: ImageProxy) {
        framesReceived.incrementAndGet()
        if (!analyzing.compareAndSet(false, true)) {
            framesDropped.incrementAndGet()
            image.close()
            return
        }

        try {
            sink.onFrame(
                image,
                FrameMetadata(
                    width = image.width,
                    height = image.height,
                    rotationDegrees = image.imageInfo.rotationDegrees,
                    timestampNanos = image.imageInfo.timestamp,
                ),
            )
        } finally {
            image.close()
            analyzing.set(false)
        }
    }

    data class FrameMetrics(
        val framesReceived: Long,
        val framesDropped: Long,
    )
}
