package com.robocam.onboardcamera.protocol

import android.util.Log
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class Esp32ConnectionState { DISCONNECTED, CONNECTING, REGISTERED, ERROR }

data class Esp32Message(
    val type: String,
    val accepted: Boolean? = null,
    val command: String? = null,
    val mode: String? = null,
    val error: String? = null,
    val left: Int? = null,
    val right: Int? = null,
    val sequence: Long? = null,
    val sessionId: String? = null,
    val signalType: String? = null,
    val payload: String? = null,
    val action: String? = null,
    val x: Float? = null,
    val y: Float? = null,
)

class Esp32WebSocketClient(
    private val url: String = Esp32Protocol.URL,
    private val onState: (Esp32ConnectionState) -> Unit,
    private val onMessage: (Esp32Message) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .build()
    private val sequence = AtomicLong(0)
    private var socket: WebSocket? = null
    private var closedByClient = false
    private val reconnectHandler = Handler(Looper.getMainLooper())

    fun connect() {
        if (socket != null) return
        closedByClient = false
        onState(Esp32ConnectionState.CONNECTING)
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, listener)
    }

    fun sendAutoRequest(): Boolean = send(Esp32Protocol.controlMode("AUTO"))

    fun sendManualRequest(): Boolean = send(Esp32Protocol.controlMode("MANUAL"))

    fun sendStop(): Boolean = send(Esp32Protocol.controlMode("STOPPED"))

    fun sendEmergencyStop(): Boolean = send(Esp32Protocol.emergencyStop())

    fun sendWebRtcSignal(sessionId: String, signalType: String, payload: String): Boolean =
        send(Esp32Protocol.webrtcSignal(sessionId, signalType, payload))

    fun sendTrackingStatus(width: Int, height: Int, selectedId: Int?, people: String): Boolean =
        send(Esp32Protocol.trackingStatus(width, height, selectedId, people))

    fun sendRecordingStatus(status: String, durationMs: Long = 0L, error: String? = null): Boolean =
        send(Esp32Protocol.recordingStatus(status, durationMs, error))

    fun sendDrive(left: Int, right: Int): Long? {
        val next = sequence.incrementAndGet()
        return if (send(Esp32Protocol.drive(left, right, next))) next else null
    }

    fun close() {
        closedByClient = true
        reconnectHandler.removeCallbacksAndMessages(null)
        socket?.close(1000, "client closing")
        socket = null
        onState(Esp32ConnectionState.DISCONNECTED)
    }

    private fun send(payload: String): Boolean =
        socket?.send(payload) == true

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(Esp32Protocol.helloMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                val message = Esp32Message(
                    type = json.optString("type"),
                    accepted = if (json.has("accepted")) json.optBoolean("accepted") else null,
                    command = json.optString("command").takeIf { it.isNotEmpty() },
                    mode = json.optString("mode").takeIf { it.isNotEmpty() },
                    error = json.optString("error").takeIf { it.isNotEmpty() },
                    left = if (json.has("left")) json.optInt("left") else null,
                    right = if (json.has("right")) json.optInt("right") else null,
                    sequence = if (json.has("seq")) json.optLong("seq") else null,
                    sessionId = json.optString("session").takeIf { it.isNotEmpty() },
                    signalType = json.optString("signalType").takeIf { it.isNotEmpty() },
                    payload = when (val signalPayload = json.opt("payload")) {
                        is JSONObject -> signalPayload.toString()
                        is String -> signalPayload
                        else -> null
                    },
                    action = json.optString("action").takeIf { it.isNotEmpty() },
                    x = if (json.has("x")) json.optDouble("x").toFloat() else null,
                    y = if (json.has("y")) json.optDouble("y").toFloat() else null,
                )
                if (message.type == "ack" && message.command == "hello") {
                    onState(Esp32ConnectionState.REGISTERED)
                }
                if (message.type == "webrtc_signal" || message.type == "webrtc_offer_request") {
                    Log.d(
                        "RoboCamEsp32",
                        "Received ${message.type} session=${message.sessionId} signal=${message.signalType}",
                    )
                }
                onMessage(message)
            } catch (error: Exception) {
                Log.e("RoboCamEsp32", "Invalid ESP32 message", error)
                onMessage(Esp32Message("error", error = "Invalid ESP32 message"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            onState(Esp32ConnectionState.ERROR)
            onMessage(
                Esp32Message(
                    type = "error",
                    error = t.message ?: "ESP32 WebSocket connection failed",
                ),
            )
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            onState(Esp32ConnectionState.DISCONNECTED)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (closedByClient || socket != null) return
        reconnectHandler.postDelayed({ if (!closedByClient) connect() }, 1_500)
    }
}
