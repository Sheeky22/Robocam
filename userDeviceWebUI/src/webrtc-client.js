export class WebRTCClient {
  constructor({ onState, onStream, signal }) {
    this.onState = onState;
    this.onStream = onStream;
    this.signal = signal;
    this.peer = null;
    this.sessionId = globalThis.crypto?.randomUUID?.() ||
      `robocam-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    this.pendingCandidates = [];
  }

  async start() {
    if (!globalThis.RTCPeerConnection) {
      this.onState("error");
      return;
    }
    this.onState("connecting");
    this.peer = new RTCPeerConnection({ iceServers: [] });
    this.peer.addTransceiver("video", { direction: "recvonly" });
    this.peer.ontrack = (event) => {
      const stream = event.streams[0] || new MediaStream([event.track]);
      this.onStream(stream);
    };
    this.peer.onicecandidate = ({ candidate }) => candidate && this.signal({
      type: "webrtc_signal",
      session: this.sessionId,
      signalType: "ice_candidate",
      payload: candidate,
    });
    this.peer.onconnectionstatechange = () => this.onState(
      this.peer.connectionState === "connected" ? "connected" :
      ["failed", "closed"].includes(this.peer.connectionState) ? "error" : "connecting",
    );
    await this.signalWithRetry({ type: "webrtc_offer_request", session: this.sessionId });
    const offer = await this.peer.createOffer();
    await this.peer.setLocalDescription(offer);
    await this.signalWithRetry({
      type: "webrtc_signal",
      session: this.sessionId,
      signalType: "offer",
      payload: this.peer.localDescription,
    });
  }

  async signalWithRetry(message, attempts = 10) {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      if (await this.signal(message)) return true;
      await new Promise((resolve) => setTimeout(resolve, 300));
    }
    this.onState("error");
    return false;
  }

  async handleSignal(message) {
    if (!this.peer) return;
    if (message.session && message.session !== this.sessionId) return;
    try {
      const payload = message.payload || message.description;
      if (message.signalType === "offer") {
        if (this.peer.signalingState !== "stable") return;
        await this.peer.setRemoteDescription(payload);
        const answer = await this.peer.createAnswer();
        await this.peer.setLocalDescription(answer);
        await this.signalWithRetry({
          type: "webrtc_signal",
          session: this.sessionId,
          signalType: "answer",
          payload: this.peer.localDescription,
        });
      } else if (message.signalType === "answer") {
        if (this.peer.signalingState !== "have-local-offer") return;
        await this.peer.setRemoteDescription(payload);
      } else if (message.signalType === "ice_candidate") {
        if (this.peer.remoteDescription) await this.peer.addIceCandidate(payload);
        else this.pendingCandidates.push(payload);
      }
      if (this.peer.remoteDescription && this.pendingCandidates.length) {
        await Promise.all(this.pendingCandidates.splice(0).map((candidate) => this.peer.addIceCandidate(candidate)));
      }
    } catch (error) {
      this.onState("error");
      console.error("RoboCam WebRTC signalling failed", error);
    }
  }

  close() {
    this.peer?.close();
    this.peer = null;
    this.onState("disconnected");
  }
}
