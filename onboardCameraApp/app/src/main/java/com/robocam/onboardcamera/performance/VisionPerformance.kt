package com.robocam.onboardcamera.performance

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class StageStats(
    val count: Long,
    val averageMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Double,
) {
    companion object {
        val EMPTY = StageStats(0, 0.0, 0.0, 0.0, 0.0)
    }
}

class RollingStageStats(private val capacity: Int = 600) {
    private val values = ArrayDeque<Double>()

    @Synchronized
    fun add(valueMs: Double) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(valueMs)
    }

    @Synchronized
    fun snapshot(): StageStats {
        if (values.isEmpty()) return StageStats.EMPTY
        val sorted = values.toList().sorted()
        return StageStats(
            count = sorted.size.toLong(),
            averageMs = sorted.average(),
            p50Ms = percentile(sorted, 0.50),
            p95Ms = percentile(sorted, 0.95),
            maxMs = sorted.last(),
        )
    }

    @Synchronized
    fun reset() = values.clear()

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        val index = kotlin.math.ceil((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}

data class PerformanceSnapshot(
    val cameraFps: Double,
    val processingFps: Double,
    val receivedFrames: Long,
    val processedFrames: Long,
    val droppedFrames: Long,
    val people: Int,
    val targetId: Int?,
    val targetDetected: Boolean,
    val targetLossEvents: Long,
    val imageConversion: StageStats,
    val preprocessing: StageStats,
    val inference: StageStats,
    val postprocessing: StageStats,
    val byteTrack: StageStats,
    val controller: StageStats,
    val endToEnd: StageStats,
    val memoryMb: Double,
    val cpuPercent: Double?,
    val thermalStatus: String,
    val deviceModel: String,
    val androidVersion: String,
    val model: String,
    val inputResolution: String,
    val precision: String,
    val runtime: String,
    val executionProvider: String,
    val acceleratorStatus: String,
    val cpuFallback: String,
    val durationSeconds: Double,
    val cameraConfiguration: String,
)

class VisionPerformance(
    private val context: Context,
    private val model: String = "YOLO11n",
    private val inputResolution: String = "320x320",
) {
    private val preprocessing = RollingStageStats()
    private val imageConversion = RollingStageStats()
    private val inference = RollingStageStats()
    private val postprocessing = RollingStageStats()
    private val byteTrack = RollingStageStats()
    private val controller = RollingStageStats()
    private val endToEnd = RollingStageStats()
    private var running = false
    private var startedAtMs = 0L
    private var received = 0L
    private var processed = 0L
    private var dropped = 0L
    private var cameraStartNs: Long? = null
    private var processingStartNs: Long? = null
    private var cameraFps = 0.0
    private var processingFps = 0.0
    private var people = 0
    private var targetId: Int? = null
    private var targetDetected = false
    private var targetLossEvents = 0L
    private var lastTargetDetected = true
    private var cpuBefore: Long? = null
    private var cpuTimeBefore: Long? = null

    @Synchronized
    fun start() {
        reset()
        running = true
        startedAtMs = System.currentTimeMillis()
    }

    @Synchronized
    fun stop() {
        running = false
    }

    @Synchronized
    fun reset() {
        listOf(imageConversion, preprocessing, inference, postprocessing, byteTrack, controller, endToEnd)
            .forEach { it.reset() }
        received = 0
        processed = 0
        dropped = 0
        cameraStartNs = null
        processingStartNs = null
        cameraFps = 0.0
        processingFps = 0.0
        startedAtMs = 0L
        targetLossEvents = 0
        lastTargetDetected = true
    }

    @Synchronized
    fun onCameraFrame(timestampNs: Long) {
        received++
        val start = cameraStartNs
        if (start == null) cameraStartNs = timestampNs
        else if (timestampNs > start) cameraFps = received * 1_000_000_000.0 / (timestampNs - start)
    }

    @Synchronized
    fun onDroppedFrame() {
        dropped++
    }

    @Synchronized
    fun onProcessed(timestampNs: Long, peopleCount: Int, id: Int?, detected: Boolean) {
        processed++
        val start = processingStartNs
        if (start == null) processingStartNs = timestampNs
        else if (timestampNs > start) {
            processingFps = processed * 1_000_000_000.0 / (timestampNs - start)
        }
        people = peopleCount
        targetId = id
        targetDetected = detected
        if (lastTargetDetected && !detected && id != null) targetLossEvents++
        lastTargetDetected = detected
    }

    fun recordPreprocessing(ms: Double) = preprocessing.add(ms)
    fun recordInference(ms: Double) = inference.add(ms)
    fun recordPostprocessing(ms: Double) = postprocessing.add(ms)
    fun recordByteTrack(ms: Double) = byteTrack.add(ms)
    fun recordController(ms: Double) = controller.add(ms)
    fun recordEndToEnd(ms: Double) = endToEnd.add(ms)

    @Synchronized
    fun snapshot(): PerformanceSnapshot {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        return PerformanceSnapshot(
            cameraFps, processingFps, received, processed, dropped, people, targetId,
            targetDetected, targetLossEvents, imageConversion.snapshot(), preprocessing.snapshot(), inference.snapshot(),
            postprocessing.snapshot(), byteTrack.snapshot(), controller.snapshot(),
            endToEnd.snapshot(), memory.totalPss / 1024.0, cpuPercent(), thermalStatus(),
            Build.MODEL, Build.VERSION.RELEASE, model, inputResolution, "FP32",
            "ONNX Runtime Android 1.20.0", "CPU (QNN not enabled)", "not active", "unknown",
            if (startedAtMs == 0L) 0.0
            else (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L) / 1000.0,
            "Preview+ImageAnalysis+VideoCapture",
        )
    }

    fun csvRow(): String {
        val s = snapshot()
        return listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
            s.deviceModel, s.androidVersion, s.model, s.inputResolution, s.precision,
            s.runtime, s.executionProvider, s.cameraFps, s.processingFps, s.receivedFrames,
            s.processedFrames, s.droppedFrames, s.imageConversion.averageMs, s.preprocessing.averageMs,
            s.inference.averageMs, s.postprocessing.averageMs, s.byteTrack.averageMs, s.controller.averageMs,
            s.endToEnd.averageMs, s.endToEnd.p50Ms, s.endToEnd.p95Ms, s.endToEnd.maxMs,
            s.memoryMb, s.cpuPercent ?: "", s.thermalStatus, s.targetLossEvents,
            s.durationSeconds, s.cameraConfiguration,
        ).joinToString(",") + "\n"
    }

    companion object {
        const val CSV_HEADER =
            "timestamp,device,android,model,input_resolution,precision,runtime,provider," +
                "camera_fps,processing_fps,received,processed,dropped,conversion_avg_ms,preprocess_avg_ms," +
                "inference_avg_ms,postprocess_avg_ms,bytetrack_avg_ms,controller_avg_ms," +
                "end_to_end_avg_ms,end_to_end_p50_ms,end_to_end_p95_ms,end_to_end_max_ms," +
                "memory_mb,cpu_percent,thermal,target_loss_events,duration_seconds,camera_configuration\n"
    }

    fun recordImageConversion(ms: Double) = imageConversion.add(ms)

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unavailable"
        val power = context.getSystemService(PowerManager::class.java)
        return when (power?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    private fun cpuPercent(): Double? {
        return try {
            val line = java.io.File("/proc/stat").readLines().first { it.startsWith("cpu ") }
            val values = line.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
            val idle = values[3] + (values.getOrNull(4) ?: 0L)
            val total = values.sum()
            val previousTotal = cpuBefore
            val previousTime = cpuTimeBefore
            cpuBefore = total
            cpuTimeBefore = idle
            if (previousTotal == null || previousTime == null) null
            else ((total - previousTotal - (idle - previousTime)).toDouble() /
                max(1L, total - previousTotal) * 100.0)
        } catch (_: Exception) {
            null
        }
    }
}
