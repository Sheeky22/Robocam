package com.robocam.onboardcamera.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteTrackTest {
    @Test
    fun stableIouMatchPreservesTrackId() {
        val tracker = ByteTrack()
        val first = tracker.update(
            listOf(PersonDetection(BoundingBox(10f, 10f, 100f, 200f), 0.9f)),
        )
        val second = tracker.update(
            listOf(PersonDetection(BoundingBox(12f, 12f, 102f, 202f), 0.8f)),
        )
        assertEquals(1, first.single().trackId)
        assertEquals(1, second.single().trackId)
    }
}
