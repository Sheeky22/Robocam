import { ESP32_WS_URL, commands, parseMessage } from "./protocol.js";

export class WebSocketClient {
  constructor({ onMessage, onState, url = ESP32_WS_URL, socketFactory = (target) => new WebSocket(target) }) {
    this.onMessage = onMessage;
    this.onState = onState;
    this.url = url;
    this.socketFactory = socketFactory;
    this.socket = null;
    this.reconnectTimer = null;
    this.closed = false;
  }

  connect() {
    this.closed = false;
    this.onState("connecting");
    try {
      this.socket = this.socketFactory(this.url);
      this.socket.addEventListener("open", () => {
        this.onState("connected");
        this.send(commands.hello());
      });
      this.socket.addEventListener("message", (event) => this.onMessage(parseMessage(event.data)));
      this.socket.addEventListener("error", () => this.onState("error"));
      this.socket.addEventListener("close", () => {
        this.onState("disconnected");
        if (!this.closed) this.scheduleReconnect();
      });
    } catch (error) {
      this.onState("error");
      this.scheduleReconnect();
    }
  }

  scheduleReconnect() {
    if (!this.reconnectTimer && !this.closed) {
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = null;
        this.connect();
      }, 1500);
    }
  }

  send(message) {
    if (this.socket?.readyState !== WebSocket.OPEN) return false;
    this.socket.send(JSON.stringify(message));
    return true;
  }

  close() {
    this.closed = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.send(commands.drive(0, 0));
    this.socket?.close();
  }
}
