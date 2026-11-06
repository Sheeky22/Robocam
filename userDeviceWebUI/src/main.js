import "./styles.css";
import { commands, KEYBOARD_DRIVE } from "./protocol.js";
import { createInitialState, markConnection, reduceState } from "./state.js";
import { createMockTransport, MockWebRTCClient } from "./mock.js";
import { WebSocketClient } from "./ws-client.js";
import { WebRTCClient } from "./webrtc-client.js";

const app = document.querySelector("#app");
const params = new URLSearchParams(location.search);
let state = createInitialState({ mock: params.get("mock") === "1" });
let transport;
let webrtc;
let activeVideoStream = null;
let recordingTimer;
let movementTimer;
let activeMovement = null;

const statusLabel = (value) => value[0].toUpperCase() + value.slice(1);
const esc = (value) => String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" })[char]);

function render() {
  const activeStream = activeVideoStream || document.querySelector("#video")?.srcObject || null;
  const elapsed = state.recordingStartedAt
    ? Math.floor((Date.now() - state.recordingStartedAt) / 1000)
    : Math.floor((state.recordingDurationMs || 0) / 1000);
  app.innerHTML = `
    <header class="topbar"><div><p class="eyebrow">ROBOCAM / LOCAL CONTROL</p><h1>Control dashboard</h1></div>
      <label class="mock-toggle"><input id="mock-toggle" type="checkbox" ${state.mock ? "checked" : ""}> Mock hardware</label>
    </header>
    <main>
      <section class="status-grid" aria-label="Connection status">
        ${statusCard("ESP32", state.esp32)}${statusCard("Android phone", state.phone)}${statusCard("WebRTC video", state.webrtc)}${statusCard("Control mode", state.controlMode.toLowerCase(), "mode")}
      </section>
      <div class="layout">
        <section class="panel video-panel"><div class="panel-heading"><h2>Live video</h2><button id="fullscreen" class="icon-button" title="Fullscreen video">↗</button></div>
          <div class="video-frame ${state.video ? "has-video" : ""}"><video id="video" autoplay playsinline></video><canvas id="tracking-overlay"></canvas><div class="video-placeholder"><span class="video-icon">◉</span><strong>${state.webrtc === "connecting" ? "Connecting to video…" : state.webrtc === "connected" ? "Waiting for video" : "Video disconnected"}</strong><small>WebRTC media is direct between Android and browser</small></div></div>
        </section>
        <aside class="side-column">
          <section class="panel"><div class="panel-heading"><h2>Control mode</h2><span class="pill ${state.controlMode.toLowerCase()}">${esc(state.controlMode)}</span></div>
            <div class="mode-buttons"><button data-mode="AUTO" class="${state.controlMode === "AUTO" ? "active" : ""}">AUTO</button><button data-mode="MANUAL" class="${state.controlMode === "MANUAL" ? "active" : ""}">MANUAL</button><button data-mode="STOPPED" class="${state.controlMode === "STOPPED" ? "active" : ""}">STOPPED</button></div>
            <p class="hint">The ESP32 confirms the active mode. Manual control requires an explicit selection.</p>
          </section>
          <section class="panel"><div class="panel-heading"><h2>Recording</h2><span class="recording-state ${state.recording}">${esc(state.recording)}</span></div><div class="timer">${String(Math.floor(elapsed / 60)).padStart(2, "0")}:${String(elapsed % 60).padStart(2, "0")}</div>
            <div class="recording-buttons"><button data-camera="START_RECORD" ${state.recording !== "idle" && state.recording !== "stopped" ? "disabled" : ""}>Start</button><button data-camera="PAUSE_RECORD" ${state.recording !== "recording" ? "disabled" : ""}>Pause</button><button data-camera="RESUME_RECORD" ${state.recording !== "paused" ? "disabled" : ""}>Resume</button><button data-camera="STOP_RECORD" ${["idle", "stopped"].includes(state.recording) ? "disabled" : ""}>Stop</button></div>
          </section>
        </aside>
      </div>
      <section class="panel movement"><div class="panel-heading"><h2>Manual movement</h2><span>Motor: ${esc(state.motor)}</span></div><div class="d-pad"><button data-drive="0,-100" ${movementDisabled()}>↶<small>Rotate left</small></button><button data-drive="100,100" ${movementDisabled()}>▲<small>Forward</small></button><button data-drive="-100,-100" ${movementDisabled()}>▼<small>Backward</small></button><button data-drive="0,100" ${movementDisabled()}>↷<small>Rotate right</small></button></div><button id="stop" class="stop-button" ${movementDisabled()}>STOP</button></section>
      <button id="emergency" class="emergency">EMERGENCY STOP <span>Immediately request motor stop</span></button>
      ${state.error ? `<p class="error" role="alert">${esc(state.error)}</p>` : ""}
    </main>
  `;
  const video = document.querySelector("#video");
  if (video && activeStream) {
    video.srcObject = activeStream;
    video.muted = true;
    video.play().catch((error) => {
      if (error.name !== "AbortError") console.warn("RoboCam video autoplay failed", error);
    });
    document.querySelector(".video-frame")?.classList.add("has-video");
  }
  bindEvents();
  drawTrackingOverlay();
  if (state.recording === "recording") {
    clearInterval(recordingTimer);
    recordingTimer = setInterval(render, 1000);
  } else clearInterval(recordingTimer);
}

function drawTrackingOverlay() {
  const video = document.querySelector("#video");
  const canvas = document.querySelector("#tracking-overlay");
  if (!video || !canvas || !state.tracking) return;
  const rect = video.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(rect.width * dpr);
  canvas.height = Math.round(rect.height * dpr);
  const context = canvas.getContext("2d");
  context.setTransform(dpr, 0, 0, dpr, 0, 0);
  context.clearRect(0, 0, rect.width, rect.height);
  const vw = video.videoWidth || rect.width;
  const vh = video.videoHeight || rect.height;
  const scale = Math.min(rect.width / vw, rect.height / vh);
  const offsetX = (rect.width - vw * scale) / 2;
  const offsetY = (rect.height - vh * scale) / 2;
  state.tracking.people?.forEach((person) => {
    context.strokeStyle = person.id === state.tracking.selectedId ? "#ffcc66" : "#6dd6c2";
    context.lineWidth = person.id === state.tracking.selectedId ? 4 : 2;
    context.strokeRect(offsetX + person.left * vw * scale, offsetY + person.top * vh * scale,
      (person.right - person.left) * vw * scale, (person.bottom - person.top) * vh * scale);
    context.fillStyle = context.strokeStyle;
    context.fillText(`#${person.id}`, offsetX + person.left * vw * scale + 4, offsetY + person.top * vh * scale + 16);
  });
}

function statusCard(label, value, kind = "") {
  return `<div class="status-card"><span class="status-dot ${value}"></span><div><small>${label}</small><strong class="${kind}">${statusLabel(value)}</strong></div></div>`;
}

function movementDisabled() {
  return state.esp32 !== "connected" || state.controlMode !== "MANUAL" ? "disabled" : "";
}

function send(message) {
  transport.send(message);
}

function stopMovement() {
  if (movementTimer) {
    clearInterval(movementTimer);
    movementTimer = null;
  }
  if (activeMovement) {
    activeMovement = null;
    send(commands.drive(0, 0));
  }
}

function startMovement(left, right) {
  stopMovement();
  activeMovement = { left, right };
  send(commands.drive(left, right));
  movementTimer = setInterval(() => {
    if (activeMovement) send(commands.drive(activeMovement.left, activeMovement.right));
  }, 200);
}

function bindEvents() {
  document.querySelector("#mock-toggle").addEventListener("change", (event) => {
    location.search = event.target.checked ? "?mock=1" : "";
  });
  document.querySelectorAll("[data-mode]").forEach((button) => button.addEventListener("click", () => {
    const mode = button.dataset.mode;
    state = { ...state, requestedMode: mode, error: null };
    send(mode === "AUTO" ? commands.phoneCommand("START_AUTO") : commands.controlMode(mode));
    render();
  }));
  document.querySelectorAll("[data-camera]").forEach((button) => button.addEventListener("click", () => {
    const action = button.dataset.camera;
    const allowed = {
      START_RECORD: ["idle", "stopped"],
      PAUSE_RECORD: ["recording"],
      RESUME_RECORD: ["paused"],
      STOP_RECORD: ["recording", "paused", "starting", "pausing", "stopping"],
    };
    if (!allowed[action]?.includes(state.recording)) return;
    if (action === "START_RECORD") state = { ...state, recording: "starting", error: null };
    if (action === "PAUSE_RECORD") state = { ...state, recording: "pausing" };
    if (action === "RESUME_RECORD") state = { ...state, recording: "starting" };
    if (action === "STOP_RECORD") state = { ...state, recording: "stopping" };
    send(commands.camera(action));
    render();
  }));
  document.querySelector("#video").addEventListener("click", (event) => {
    const video = event.currentTarget;
    const rect = video.getBoundingClientRect();
    const sourceWidth = video.videoWidth || rect.width;
    const sourceHeight = video.videoHeight || rect.height;
    const scale = Math.min(rect.width / sourceWidth, rect.height / sourceHeight);
    const displayedWidth = sourceWidth * scale;
    const displayedHeight = sourceHeight * scale;
    const offsetX = (rect.width - displayedWidth) / 2;
    const offsetY = (rect.height - displayedHeight) / 2;
    if (event.clientX < rect.left + offsetX ||
        event.clientX > rect.left + offsetX + displayedWidth ||
        event.clientY < rect.top + offsetY ||
        event.clientY > rect.top + offsetY + displayedHeight) return;
    send(commands.targetSelect(
      (event.clientX - rect.left - offsetX) / displayedWidth,
      (event.clientY - rect.top - offsetY) / displayedHeight,
    ));
  });
  document.querySelectorAll("[data-drive]").forEach((button) => {
    const [left, right] = button.dataset.drive.split(",").map(Number);
    button.addEventListener("pointerdown", (event) => {
      event.preventDefault();
      button.setPointerCapture?.(event.pointerId);
      startMovement(left, right);
    });
    ["pointerup", "pointercancel"].forEach((eventName) => button.addEventListener(eventName, stopMovement));
  });
  document.querySelector("#stop").addEventListener("click", stopMovement);
  document.querySelector("#emergency").addEventListener("click", () => {
    stopMovement();
    send(commands.emergencyStop());
  });
  document.querySelector("#fullscreen").addEventListener("click", () => document.querySelector(".video-frame").requestFullscreen?.());
}

function handleMessage(message) {
  if (message.type === "webrtc_signal") webrtc?.handleSignal(message);
  if (message.type === "recording_status" && message.status === "error") {
    state = { ...state, error: message.error || "Phone recording failed" };
  }
  state = reduceState(state, message);
  if (message.type === "tracking_status") {
    drawTrackingOverlay();
    return;
  }
  render();
}

function setup() {
  const onState = (status) => { state = markConnection(state, "esp32", status); render(); };
  transport = state.mock
    ? createMockTransport(handleMessage)
    : new WebSocketClient({ onMessage: handleMessage, onState });
  if (state.mock) transport.send(commands.hello()); else transport.connect();
  const webRtcOptions = {
    onState: (status) => { state = markConnection(state, "webrtc", status); render(); },
    onStream: (stream) => {
      activeVideoStream = stream;
      render();
    },
    signal: (message) => send(message),
  };
  webrtc = state.mock ? new MockWebRTCClient(webRtcOptions) : new WebRTCClient(webRtcOptions);
  webrtc.start();
  window.addEventListener("keydown", (event) => {
    const movement = KEYBOARD_DRIVE[event.key];
    if (!movement || event.repeat || movementDisabled()) return;
    event.preventDefault();
    startMovement(movement[0], movement[1]);
  });
  window.addEventListener("keyup", (event) => {
    if (KEYBOARD_DRIVE[event.key]) {
      event.preventDefault();
      stopMovement();
    }
  });
  window.addEventListener("blur", stopMovement);
  window.addEventListener("beforeunload", () => { stopMovement(); transport.close?.(); webrtc.close(); });
  render();
}

setup();
