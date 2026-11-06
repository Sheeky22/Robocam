package com.robocam.onboardcamera.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingControllerTest {
    private fun person(id: Int, centerX: Float, height: Float = 400f): TrackedPerson =
        TrackedPerson(
            box = BoundingBox(centerX - 100f, 100f, centerX + 100f, 100f + height),
            confidence = 0.9f,
            trackId = id,
        )

    @Test
    fun pythonSequenceMatchesEmaAndHysteresis() {
        val controller = TrackingController()
        val initial = listOf(person(7, 500f, 400f))
        controller.selectAtFrameCoordinates(500f, 300f, 1000, initial)

        val first = controller.update(initial, 1000, 1000, 1_000_000_000L, 12.0)
        assertEquals(RotationDecision.STOP, first.rotation)
        assertEquals(DistanceDecision.STOP, first.distance)

        val second = controller.update(
            listOf(person(7, 700f, 500f)),
            1000,
            1000,
            1_033_333_333L,
            11.0,
        )
        assertEquals(0.1f, second.horizontalError!!, 0.0001f)
        assertEquals(0.0625f, second.scaleError!!, 0.0001f)
        assertEquals(RotationDecision.RIGHT, second.rotation)
        assertEquals(DistanceDecision.STOP, second.distance)

        val third = controller.update(
            listOf(person(7, 540f, 410f)),
            1000,
            1000,
            1_066_666_666L,
            10.0,
        )
        assertEquals(RotationDecision.RIGHT, third.rotation)
        assertEquals(DistanceDecision.STOP, third.distance)
    }

    @Test
    fun missingSelectedTargetStopsAndClearsSmoothedErrors() {
        val controller = TrackingController()
        val selected = listOf(person(3, 500f))
        controller.selectAtFrameCoordinates(500f, 300f, 1000, selected)
        controller.update(selected, 1000, 1000, 1_000_000_000L, 1.0)

        val lost = controller.update(emptyList(), 1000, 1000, 1_033_333_333L, 1.0)
        assertEquals(RotationDecision.STOP, lost.rotation)
        assertEquals(DistanceDecision.STOP, lost.distance)
        assertNull(lost.horizontalError)
        assertNull(lost.scaleError)
        assertEquals(3, lost.targetId)
    }

    @Test
    fun normalizedSelectionUsesFrameCoordinates() {
        val controller = TrackingController()
        val people = listOf(person(4, 750f))
        controller.selectAtNormalizedCoordinates(0.75f, 0.3f, 1000, 1000, people)
        val output = controller.update(people, 1000, 1000, 1_000_000_000L, 1.0)
        assertEquals(4, output.targetId)
    }

    @Test
    fun leftDecisionRemainsDistinctFromDistanceStop() {
        val controller = TrackingController()
        val selected = listOf(person(5, 500f, 400f))
        controller.selectAtFrameCoordinates(500f, 300f, 1000, selected)
        val output = controller.update(
            listOf(person(5, 700f, 400f)),
            1000,
            1000,
            1_000_000_000L,
            1.0,
        )
        assertEquals(RotationDecision.RIGHT, output.rotation)
        assertEquals(DistanceDecision.STOP, output.distance)
    }
}
