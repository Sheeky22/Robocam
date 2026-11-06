package com.robocam.onboardcamera.protocol

object Esp32Protocol {
    const val PHONE_ROLE = "phone"
    const val URL = "ws://192.168.4.1:81/ws"

    fun helloMessage(role: String = PHONE_ROLE): String =
        """{"type":"hello","role":"$role"}"""

    fun controlMode(mode: String): String =
        """{"type":"control_mode","mode":"$mode"}"""

    fun drive(left: Int, right: Int, sequence: Long): String =
        """{"type":"drive","left":$left,"right":$right,"seq":$sequence}"""

    fun emergencyStop(): String = """{"type":"emergency_stop"}"""

    fun webrtcSignal(sessionId: String, signalType: String, payload: String): String =
        """{"type":"webrtc_signal","session":"${escape(sessionId)}","signalType":"${escape(signalType)}","payload":$payload}"""

    fun webrtcOfferRequest(sessionId: String): String =
        """{"type":"webrtc_offer_request","session":"${escape(sessionId)}"}"""

    fun trackingStatus(
        width: Int,
        height: Int,
        selectedId: Int?,
        people: String,
    ): String =
        """{"type":"tracking_status","width":$width,"height":$height,"selectedId":${selectedId ?: "null"},"people":$people}"""

    fun recordingStatus(status: String, durationMs: Long = 0L, error: String? = null): String =
        """{"type":"recording_status","status":"${escape(status)}","durationMs":$durationMs${error?.let { ",\"error\":\"${escape(it)}\""} ?: ""}}"""

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
