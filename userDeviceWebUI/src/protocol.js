export const ESP32_HTTP_URL = "http://192.168.4.1";
export const ESP32_WS_URL = "ws://192.168.4.1:81/ws";

export const CONTROL_MODES = Object.freeze(["AUTO", "MANUAL", "STOPPED"]);
export const CONNECTION_STATES = Object.freeze(["disconnected", "connecting", "connected", "error"]);
export const KEYBOARD_DRIVE = Object.freeze({
  ArrowUp: [100, 100],
  ArrowDown: [-100, -100],
  ArrowLeft: [0, -100],
  ArrowRight: [0, 100]
});

export function parseMessage(raw) {
  try {
    const message = typeof raw === "string" ? JSON.parse(raw) : raw;
    if (!message || typeof message !== "object" || typeof message.type !== "string") {
      return { type: "error", error: "Invalid message shape" };
    }
    return message;
  } catch {
    return { type: "error", error: "Invalid JSON message" };
  }
}

export const commands = Object.freeze({
  hello: () => ({ type: "hello", role: "web" }),
  controlMode: (mode) => ({ type: "control_mode", mode }),
  drive: (left, right) => ({ type: "drive", left, right }),
  emergencyStop: () => ({ type: "emergency_stop" }),
  camera: (action) => ({ type: "camera", action })
  ,
  phoneCommand: (action) => ({ type: "phone_command", action }),
  targetSelect: (x, y) => ({ type: "target_select", x, y })
});
