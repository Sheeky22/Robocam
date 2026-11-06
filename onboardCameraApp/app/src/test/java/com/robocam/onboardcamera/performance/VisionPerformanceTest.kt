package com.robocam.onboardcamera.performance

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test

class VisionPerformanceTest {
    @Test
    fun rollingStatsCalculateAverageAndPercentiles() {
        val stats = RollingStageStats(5)
        listOf(1.0, 2.0, 3.0, 4.0, 5.0).forEach(stats::add)
        val result = stats.snapshot()
        assertEquals(5, result.count)
        assertEquals(3.0, result.averageMs, 0.001)
        assertEquals(3.0, result.p50Ms, 0.001)
        assertEquals(5.0, result.p95Ms, 0.001)
        assertEquals(5.0, result.maxMs, 0.001)
    }
}
