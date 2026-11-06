package com.robocam.onboardcamera.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class Esp32ProtocolTest {
    @Test
    fun helloDefaultsToPhoneRole() {
        assertEquals("""{"type":"hello","role":"phone"}""", Esp32Protocol.helloMessage())
    }

    @Test
    fun helloSupportsExplicitRoleForProtocolClients() {
        assertEquals("""{"type":"hello","role":"web"}""", Esp32Protocol.helloMessage("web"))
    }

    @Test
    fun autonomousCommandsIncludeSequence() {
        assertEquals(
            """{"type":"control_mode","mode":"AUTO"}""",
            Esp32Protocol.controlMode("AUTO"),
        )
        assertEquals(
            """{"type":"drive","left":20,"right":25,"seq":17}""",
            Esp32Protocol.drive(20, 25, 17),
        )
    }

    @Test
    fun manualModeRequestUsesSharedControlModeProtocol() {
        assertEquals(
            """{"type":"control_mode","mode":"MANUAL"}""",
            Esp32Protocol.controlMode("MANUAL"),
        )
    }

    @Test
    fun webRtcSignalContainsSessionAndPayload() {
        assertEquals(
            """{"type":"webrtc_signal","session":"s1","signalType":"answer","payload":{"type":"answer"}}""",
            Esp32Protocol.webrtcSignal("s1", "answer", """{"type":"answer"}"""),
        )
    }
}
