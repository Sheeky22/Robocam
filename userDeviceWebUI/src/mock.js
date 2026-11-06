import { commands } from "./protocol.js";

export class MockSocket {
  constructor(onMessage) {
    this.onMessage = onMessage;
    this.sent = [];
    this.readyState = 1;
  }

  send(payload) {
    this.sent.push(JSON.parse(payload));
  }

  close() {
    this.readyState = 3;
  }

  receive(message) {
    this.onMessage(JSON.stringify(message));
  }
}

export function createMockTransport(onMessage) {
  const socket = new MockSocket(onMessage);
  queueMicrotask(() => socket.receive({ type: "connection", target: "esp32", state: "connected" }));
  return {
    socket,
    connect() {
      socket.receive({ type: "connection", target: "esp32", state: "connected" });
    },
    close() {
      socket.close();
      socket.receive({ type: "connection", target: "esp32", state: "disconnected" });
    },
    send(message) {
      socket.send(JSON.stringify(message));
      if (message.type === "control_mode") {
        queueMicrotask(() => socket.receive({ type: "ack", command: "control_mode", accepted: true }));
        queueMicrotask(() => socket.receive({ type: "control_mode", mode: message.mode }));
      }
      if (message.type === "drive") {
        queueMicrotask(() => socket.receive({ type: "motor_status", status: message.left || message.right ? "moving" : "stopped" }));
      }
      if (message.type === "camera") {
        const status = { START_RECORD: "recording", PAUSE_RECORD: "paused", RESUME_RECORD: "recording", STOP_RECORD: "stopped" }[message.action];
        if (status) queueMicrotask(() => socket.receive({ type: "recording_status", status }));
      }
    }
  };
}

export function mockHello() {
  return commands.hello();
}

export class MockWebRTCClient {
  constructor({ onState, onStream, signal }) {
    this.onState = onState;
    this.onStream = onStream;
    this.signal = signal;
  }

  async start() {
    this.onState("connecting");
    await this.signal({ type: "webrtc_offer_request" });
    queueMicrotask(() => this.onState("connected"));
  }

  async handleSignal() {}

  close() {
    this.onState("disconnected");
  }
}
