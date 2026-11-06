# Android/ESP32 autonomous-control protocol

The Android phone connects directly to the ESP32 access point at
`ws://192.168.4.1:81/ws` and registers as:

```json
{"type":"hello","role":"phone"}
```

The ESP32 acknowledges registration with:

```json
{"type":"ack","command":"hello","accepted":true,"role":"phone"}
```

## Autonomous mode

After a target is selected and the user explicitly starts AUTO, Android sends:

```json
{"type":"control_mode","mode":"AUTO"}
```

The ESP32 may accept this only for the registered phone client. Android then
sends strictly increasing sequence-numbered drive commands:

```json
{"type":"drive","left":20,"right":25,"seq":17}
```

Values are differential-drive percentages. The ESP32 rejects stale sequences,
rejects autonomous values above 100 percent, and stops after 750 ms without a
valid command. Android repeats the current command at least every 200 ms while
AUTO is active.

Android sends:

```json
{"type":"control_mode","mode":"STOPPED"}
{"type":"emergency_stop"}
```

when the user stops, the app pauses, the selected target is lost, vision
processing fails, or the emergency control is pressed.

## Phone manual test mode

The phone can request the same manual mode used by the browser:

```json
{"type":"control_mode","mode":"MANUAL"}
{"type":"drive","left":100,"right":100,"seq":1}
```

The phone UI sends a drive command every 200 ms while an arrow is held and
sends `left:0,right:0` when it is released. The ESP32 watchdog independently
stops the motors if those refreshes stop.

## Authority and safety

The browser can still override phone MANUAL or Android AUTO by selecting
MANUAL or STOPPED. The ESP32 independently stops motors on phone disconnect,
malformed or unauthorized commands, stale sequences, and watchdog expiry.
WebRTC is not required for this control path.

## WebRTC signalling

The browser and phone use a shared session identifier for SDP and ICE
messages. The ESP32 forwards JSON signalling only; video media travels
directly between the browser and phone as WebRTC RTP/RTCP.

The browser may also relay `camera` recording actions and normalized
`target_select` coordinates to the phone. The phone applies those commands
against its existing CameraX recording and tracking pipeline.
