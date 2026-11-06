package com.robocam.onboardcamera.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PreviewCoordinateMapperTest {
    @Test
    fun mapsCenterThroughCenterCropWithoutRotation() {
        val point = PreviewCoordinateMapper.viewToFrame(
            viewX = 500f,
            viewY = 500f,
            viewWidth = 1000,
            viewHeight = 1000,
            frameWidth = 1920,
            frameHeight = 1080,
            rotationDegrees = 0,
        )
        assertNotNull(point)
        assertEquals(960f, point!!.x, 1f)
        assertEquals(540f, point.y, 1f)
    }

    @Test
    fun mapsRotatedFrameCenter() {
        val point = PreviewCoordinateMapper.viewToFrame(
            viewX = 500f,
            viewY = 500f,
            viewWidth = 1000,
            viewHeight = 1000,
            frameWidth = 1920,
            frameHeight = 1080,
            rotationDegrees = 90,
        )
        assertNotNull(point)
        assertEquals(960f, point!!.x, 1f)
        assertEquals(540f, point.y, 1f)
    }
}
