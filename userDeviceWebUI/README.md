# RoboCam Web Dashboard

Responsive, browser-only control dashboard for the RoboCam ESP32 access point. The frontend is vanilla HTML/CSS/ES modules bundled by Vite, so the generated `dist/` directory can be served directly by the ESP32 HTTP server.

## Local development

```sh
cd userDeviceWebUI
npm install
npm run dev
```

Open the displayed local URL. Enable **Mock hardware** (or open `/?mock=1`) to exercise mode, movement, recording, and connection UI without hardware. Mock acknowledgements are simulated and are not evidence that a physical motor or camera acted.

```sh
npm test
npm run build
```

## Deployment

1. Run `npm run build`.
2. Upload the contents of `dist/` to the ESP32's HTTP filesystem.
3. Configure the ESP32 to serve `index.html` for the dashboard route.
4. Ensure the browser is connected to the ESP32 access point (`192.168.4.1`).

The production client connects to `ws://192.168.4.1:81/ws` and registers with
`{"type":"hello","role":"web"}`. It sends a drive stop on button release,
window blur, unload, and transport shutdown. The ESP32 remains authoritative
for motor safety and relays phone recording, target, tracking, and WebRTC
signalling messages.

## WebSocket message contract

Dashboard commands use these JSON shapes:

* `{"type":"hello","role":"web"}`
* `{"type":"control_mode","mode":"MANUAL|AUTO|STOPPED"}`
* `{"type":"drive","left":-100..100,"right":-100..100}`
* `{"type":"emergency_stop"}`
* `{"type":"camera","action":"START_RECORD|PAUSE_RECORD|RESUME_RECORD|STOP_RECORD"}`

Expected device messages include `connection` (`target`, `state`),
`phone_status` (`connected`), `control_mode` (`mode`),
`motor_status` (`status`), `recording_status` (`status`),
`tracking_status`, `ack` (`command`, `accepted`, optional `error`), `error`,
and `webrtc_signal`.

## WebRTC signalling

The browser creates a receive-only `RTCPeerConnection` with host ICE
candidates, uses a session-scoped offer/answer exchange, and queues ICE until
the remote description is installed. The ESP32 relays JSON signalling only;
media is direct between the browser and Android. The video element accepts
clicks for normalized target selection, and recording/AUTO actions are
relayed to the phone.

## Deployment limitations

Host ICE is limited to the local RoboCam Wi-Fi network; public internet
connectivity and NAT traversal are not supported in this phase. Physical
browser/phone/ESP32 connection, latency, repeated reconnect, and screen-off
behavior still require device testing.
