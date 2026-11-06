package com.robocam.onboardcamera.webrtc

import android.content.Context
import androidx.camera.core.ImageProxy
import com.robocam.onboardcamera.camera.FrameMetadata
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.util.Log

class AndroidWebRtcClient(
    context: Context,
    private val sendSignal: (sessionId: String, signalType: String, payload: String) -> Boolean,
    private val onState: (State) -> Unit,
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val videoSource: VideoSource
    private val videoTrack: VideoTrack
    private var peer: PeerConnection? = null
    private var sessionId: String? = null
    private val started = AtomicBoolean(false)
    private val acceptingFrames = AtomicBoolean(false)
    private val capturerStarted = AtomicBoolean(false)
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private val acceptedFrameCount = AtomicLong(0)
    private val publishedFrameCount = AtomicLong(0)
    private var lastPublishedTimestampNanos: Long? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        videoSource = factory.createVideoSource(false)
        videoTrack = factory.createVideoTrack("robocam-video", videoSource)
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Log.d(TAG, "Starting WebRTC client")
        sessionId = null
        lastPublishedTimestampNanos = null
        acceptedFrameCount.set(0)
        publishedFrameCount.set(0)
        videoSource.capturerObserver.onCapturerStarted(true)
        capturerStarted.set(true)
        acceptingFrames.set(true)
        onState(State.CONNECTING)
        createPeer()
    }

    fun bindSession(remoteSessionId: String) {
        if (remoteSessionId.isBlank()) return
        if (sessionId != null && sessionId != remoteSessionId) {
            Log.d(TAG, "Replacing stale WebRTC peer for session=$remoteSessionId")
            peer?.close()
            synchronized(pendingCandidates) { pendingCandidates.clear() }
            peer = null
            createPeer()
        }
        sessionId = remoteSessionId
    }

    fun handleSignal(remoteSessionId: String, signalType: String, payload: String) {
        Log.d(TAG, "Handling signal type=$signalType session=$remoteSessionId")
        if (sessionId != null && remoteSessionId != sessionId) bindSession(remoteSessionId)
        else if (sessionId == null) bindSession(remoteSessionId)
        if (remoteSessionId != sessionId) return
        val connection = peer ?: return
        when (signalType) {
            "offer" -> {
                if (connection.signalingState() != PeerConnection.SignalingState.STABLE) {
                    Log.d(TAG, "Ignoring offer in state=${connection.signalingState()}")
                    return
                }
                val json = JSONObject(payload)
                connection.setRemoteDescription(
                    remoteOfferObserver,
                    SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(json.getString("type")),
                        json.getString("sdp"),
                    ),
                )
            }
            "ice_candidate" -> {
                val json = JSONObject(payload)
                val candidate = IceCandidate(
                        json.optString("sdpMid"),
                        json.optInt("sdpMLineIndex"),
                        json.getString("candidate"),
                    )
                if (connection.remoteDescription != null) connection.addIceCandidate(candidate)
                else synchronized(pendingCandidates) { pendingCandidates += candidate }
            }
        }
    }

    fun pushFrame(image: ImageProxy, metadata: FrameMetadata) {
        if (!started.get() || !acceptingFrames.get()) return
        val lastPublished = lastPublishedTimestampNanos
        if (lastPublished != null &&
            metadata.timestampNanos - lastPublished < FRAME_INTERVAL_NANOS
        ) return
        lastPublishedTimestampNanos = metadata.timestampNanos
        val accepted = acceptedFrameCount.incrementAndGet()
        val observer = videoSource.capturerObserver
        val buffer = JavaI420Buffer.allocate(image.width, image.height)
        copyPlane(image.planes[0].buffer, image.planes[0].rowStride, image.planes[0].pixelStride, buffer.dataY, buffer.strideY, image.width, image.height)
        copyPlane(image.planes[1].buffer, image.planes[1].rowStride, image.planes[1].pixelStride, buffer.dataU, buffer.strideU, (image.width + 1) / 2, (image.height + 1) / 2)
        copyPlane(image.planes[2].buffer, image.planes[2].rowStride, image.planes[2].pixelStride, buffer.dataV, buffer.strideV, (image.width + 1) / 2, (image.height + 1) / 2)
        observer.onFrameCaptured(
            VideoFrame(
                buffer,
                metadata.rotationDegrees,
                metadata.timestampNanos / 1_000_000L,
            ),
        )
        val published = publishedFrameCount.incrementAndGet()
        if (published == 1L || published % FRAME_LOG_INTERVAL == 0L) {
            Log.d(TAG, "Published WebRTC frame accepted=$accepted published=$published")
        }
    }

    fun stopFramePublishing() {
        acceptingFrames.set(false)
    }

    fun close() {
        if (!started.compareAndSet(true, false)) return
        acceptingFrames.set(false)
        if (capturerStarted.compareAndSet(true, false)) {
            videoSource.capturerObserver.onCapturerStopped()
        }
        peer?.close()
        peer = null
        videoTrack.dispose()
        videoSource.dispose()
        factory.dispose()
        eglBase.release()
        onState(State.DISCONNECTED)
    }

    private fun createPeer() {
        peer = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            },
            observer,
        )
        peer?.addTrack(videoTrack, listOf("robocam"))
    }

    private fun copyPlane(
        source: ByteBuffer,
        sourceStride: Int,
        sourcePixelStride: Int,
        destination: ByteBuffer,
        destinationStride: Int,
        width: Int,
        height: Int,
    ) {
        val input = source.duplicate()
        val output = destination.duplicate()
        repeat(height) { y ->
            val rowStart = y * sourceStride
            for (x in 0 until width) {
                val sourceIndex = rowStart + x * sourcePixelStride
                if (sourceIndex >= input.limit()) break
                output.put(y * destinationStride + x, input.get(sourceIndex))
            }
        }
    }

    private val remoteOfferObserver = object : org.webrtc.SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() {
            Log.d(TAG, "Remote offer applied; creating answer")
            synchronized(pendingCandidates) {
                pendingCandidates.forEach { peer?.addIceCandidate(it) }
                pendingCandidates.clear()
            }
            peer?.createAnswer(sdpObserver, MediaConstraints())
        }
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "Remote offer create failure: $error")
            onState(State.FAILED)
        }
        override fun onSetFailure(error: String) {
            Log.e(TAG, "Remote offer set failure: $error")
            onState(State.FAILED)
        }
    }

    private val sdpObserver = object : org.webrtc.SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {
            peer?.setLocalDescription(this, description)
            val id = sessionId ?: return
            Log.d(TAG, "Sending local ${description.type.canonicalForm()} session=$id")
            sendSignal(
                id,
                if (description.type == SessionDescription.Type.ANSWER) "answer" else "offer",
                JSONObject()
                    .put("type", description.type.canonicalForm())
                    .put("sdp", description.description)
                    .toString(),
            )
        }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "Local SDP create failure: $error")
            onState(State.FAILED)
        }
        override fun onSetFailure(error: String) {
            Log.e(TAG, "Local SDP set failure: $error")
            onState(State.FAILED)
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val id = sessionId ?: return
            Log.d(TAG, "Sending ICE candidate session=$id")
            sendSignal(
                id,
                "ice_candidate",
                JSONObject()
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)
                    .toString(),
            )
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.d(TAG, "Peer connection state=$newState")
            onState(
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> State.CONNECTED
                    PeerConnection.PeerConnectionState.FAILED -> State.FAILED
                    PeerConnection.PeerConnectionState.CLOSED -> State.DISCONNECTED
                    else -> State.CONNECTING
                },
            )
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) = Unit
        override fun onTrack(transceiver: RtpTransceiver) = Unit
    }

    private companion object {
        const val TAG = "RoboCamWebRTC"
        const val FRAME_INTERVAL_NANOS = 66_666_667L
        const val FRAME_LOG_INTERVAL = 30L
    }
}
