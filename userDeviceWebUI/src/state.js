export function createInitialState({ mock = false } = {}) {
  return {
    mock,
    esp32: "disconnected",
    phone: "disconnected",
    webrtc: "disconnected",
    controlMode: "STOPPED",
    requestedMode: null,
    motor: "stopped",
    recording: "idle",
    recordingStartedAt: null,
    recordingDurationMs: 0,
    error: null,
    video: null
    ,tracking: null
  };
}

export function reduceState(state, message) {
  const next = { ...state };
  switch (message.type) {
    case "connection":
      if (message.target === "esp32" || message.target === "phone" || message.target === "webrtc") {
        next[message.target] = message.state;
      }
      break;
    case "control_mode":
      if (typeof message.mode === "string") {
        next.controlMode = message.mode;
        next.requestedMode = null;
      }
      break;
    case "motor_status":
      if (typeof message.status === "string") next.motor = message.status;
      if (typeof message.mode === "string") next.controlMode = message.mode;
      if (message.emergency === true) next.error = "Emergency stop is active";
      break;
    case "recording_status":
      if (typeof message.status === "string") {
        if (message.status === "error") {
          if (next.recording === "starting" || next.recording === "stopping") {
            next.recording = "idle";
          }
          next.error = message.error || "Recording command failed";
        } else {
          next.recording = message.status;
        }
        if (message.status === "recording" && !next.recordingStartedAt) {
          next.recordingStartedAt = Date.now();
        }
        if (["idle", "stopped", "error"].includes(message.status)) next.recordingStartedAt = null;
      }
      if (typeof message.durationMs === "number") next.recordingDurationMs = message.durationMs;
      break;
    case "phone_status":
      next.phone = message.connected ? "connected" : "disconnected";
      break;
    case "webrtc_signal":
      next.video = message;
      break;
    case "tracking_status":
      next.tracking = message;
      break;
    case "ack":
      if (message.accepted === false) {
        if (message.command === "control_mode") next.requestedMode = null;
        if (message.command === "camera") next.recording = "idle";
        next.error = message.error || `${message.command || "Command"} rejected`;
      }
      break;
    case "error":
      next.error = message.error || "Unknown device error";
      break;
    default:
      break;
  }
  return next;
}

export function markConnection(state, target, status) {
  return { ...state, [target]: status, ...(status !== "connected" && target === "esp32" ? { motor: "stopped" } : {}) };
}
