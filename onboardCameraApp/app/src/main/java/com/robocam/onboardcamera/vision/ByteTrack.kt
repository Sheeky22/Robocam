package com.robocam.onboardcamera.vision

import kotlin.math.max

/**
 * A bounded, two-stage IoU association tracker for person detections.
 * High-confidence detections are associated first; lower-confidence
 * detections may recover an existing track but never create a new one.
 */
class ByteTrack(
    private val highConfidence: Float = 0.5f,
    private val lowConfidence: Float = 0.1f,
    private val matchThreshold: Float = 0.3f,
    private val maxMisses: Int = 30,
) {
    private data class Track(
        var box: BoundingBox,
        var confidence: Float,
        val id: Int,
        var misses: Int = 0,
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    fun update(detections: List<PersonDetection>): List<TrackedPerson> {
        val candidates = detections.filter { it.classId == 0 && it.confidence >= lowConfidence }
        val unmatched = candidates.toMutableList()
        val matched = mutableSetOf<Int>()

        fun associate(allowed: (PersonDetection) -> Boolean) {
            tracks.sortedByDescending { it.confidence }.forEach { track ->
                val best = unmatched
                    .filter(allowed)
                    .map { it to iou(track.box, it.box) }
                    .maxByOrNull { it.second }
                if (best != null && best.second >= matchThreshold) {
                    track.box = best.first.box
                    track.confidence = best.first.confidence
                    track.misses = 0
                    matched += track.id
                    unmatched.remove(best.first)
                }
            }
        }

        associate { it.confidence >= highConfidence }
        associate { true }

        tracks.filter { it.id !in matched }.forEach { it.misses++ }
        unmatched.filter { it.confidence >= highConfidence }.forEach {
            tracks += Track(it.box, it.confidence, nextId++)
        }
        tracks.removeAll { it.misses > maxMisses }

        return tracks
            .filter { it.misses == 0 }
            .map { TrackedPerson(it.box, it.confidence, it.id) }
    }

    fun reset() {
        tracks.clear()
        nextId = 1
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val intersectionWidth = max(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val intersectionHeight = max(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val intersection = intersectionWidth * intersectionHeight
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union > 0f) intersection / union else 0f
    }
}
