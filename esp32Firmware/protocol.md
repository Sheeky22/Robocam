# RoboCam ESP32 L298N protocol

The ESP32 creates the `RoboCam` access point (`robocam123`) at
`192.168.4.1`:

- Dashboard: `http://192.168.4.1/`
- WebSocket: `ws://192.168.4.1:81/ws`

The Android phone may use the same WebSocket as a registered `phone` client
for autonomous control. Browser control remains available for manual override.

## Hardware

| L298N signal | ESP32 GPIO | Function |
| --- | ---: | --- |
| ENA | 21 | Motor A PWM |
| ENB | 19 | Motor B PWM |
| IN1 / IN2 | 18 / 17 | Motor A direction |
| IN3 / IN4 | 16 / 4 | Motor B direction |

PWM is 30 kHz, 8-bit. Motor power, ESP32 ground, and the L298N ground must
share a common ground. Keep the wheels off the ground during initial tests.

## Registration

Each WebSocket client sends one message after opening:

```json
{"type":"hello","role":"web"}
{"type":"hello","role":"phone"}
```

The firmware tracks one client per role. The registered `web` client controls MANUAL/STOPPED. The registered `phone`
client may request MANUAL for direct onboard testing, or AUTO for autonomous
control.

## Motor commands

Select manual mode before driving:

```json
{"type":"control_mode","mode":"MANUAL"}
```

Drive values are percentages from `-100` to `100`:

```json
{"type":"drive","left":50,"right":50}
```

Positive and negative directions depend on motor polarity. Values outside the range are clamped. Both the browser and the registered
phone may send manual drive commands after selecting `MANUAL`. Stop or
emergency-stop:

```json
{"type":"control_mode","mode":"STOPPED"}
{"type":"emergency_stop"}
```

The phone requests autonomous mode with:

```json
{"type":"control_mode","mode":"AUTO"}
```

After an accepted request, phone drive commands must include a strictly
increasing sequence number:

```json
{"type":"drive","left":20,"right":25,"seq":17}
```

Autonomous output is capped to 100 percent per motor by the ESP32. Commands
above that limit are rejected. The phone may also send:

```json
{"type":"heartbeat"}
```

The browser's `MANUAL` or `STOPPED` command immediately overrides AUTO.

Accepted commands receive an acknowledgement:

```json
{"type":"ack","command":"drive","accepted":true}
```

Rejected commands receive `accepted:false` and an `error` field. The dashboard
also receives live status messages:

```json
{"type":"control_mode","mode":"MANUAL"}
{"type":"motor_status","status":"moving","left":50,"right":50,"mode":"MANUAL","emergency":false}
```

## Safety behavior

- Motors are stopped during boot.
- Motors stop when the web client disconnects.
- Motors stop when `emergency_stop` is received.
- Motors stop after 750 ms without a new `drive` command.
- Autonomous commands with missing or stale sequence numbers are rejected.
- Phone disconnect, Android lifecycle stop, target loss, and unhealthy vision
  must result in a zero drive command; the ESP32 watchdog remains independent.

## WebRTC signalling

The browser and phone relay SDP and ICE through the ESP32 using a shared
session identifier. The ESP32 forwards JSON signalling only and never
receives or forwards RTP/RTCP media.

Browser recording commands use the existing `camera` messages and browser
target taps use:

```json
{"type":"target_select","x":0.5,"y":0.5}
```

The ESP32 relays these control messages to the phone; the phone remains the
component that owns the camera, recording, and tracking state.
- `STOPPED`, rejected, malformed, and unauthorized commands never energize motors.
