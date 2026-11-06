# Phase 1 test report

## Automated validation

| Check | Result |
|---|---|
| Kotlin unit tests (`./gradlew testDebugUnitTest`) | Passed |
| Debug APK (`./gradlew assembleDebug`) | Passed |
| APK output | `app/build/outputs/apk/debug/app-debug.apk` |
| Instrumentation smoke test | Not run: requires an emulator or connected Android device |

The build emitted only a Kotlin Gradle DSL deprecation warning for
`kotlinOptions`; it did not fail the build.

## Manual device checklist

Run this checklist on the Galaxy S25 first, then repeat on at least one other
compatible Android device:

- [ ] Launch the app.
- [ ] Grant camera permission.
- [ ] Confirm live rear-camera preview.
- [ ] Confirm camera state changes from `initializing` to `running`.
- [ ] Confirm frame width, height, and rotation are displayed.
- [ ] Confirm analysis FPS is displayed and updates.
- [ ] Rotate the phone and confirm rotation metadata updates.
- [ ] Confirm the frame count continues increasing while the camera is
  running; it represents analyzed frames, not a one-time initialization count.
- [ ] Background and foreground the app; confirm preview and FPS recover.
- [ ] Leave the app running for at least 10 minutes and check that memory does
  not continuously grow.
- [ ] Revoke and re-grant camera permission; confirm the camera starts again.

## Phase 3 local recording validation

### Automated validation

| Check | Result |
|---|---|
| Kotlin unit tests (`./gradlew testDebugUnitTest`) | Passed |
| Debug APK (`./gradlew assembleDebug`) | Passed |
| Recording state/error tests | Passed |
| Physical MP4 playback and MediaStore verification | Not run: requires Android device |

### Device checklist

- [ ] Start recording and confirm state changes to `recording`.
- [ ] Pause and resume; confirm duration does not reset.
- [ ] Stop and confirm a playable MP4 appears in `Movies/RoboCam`.
- [ ] Verify file size and playback duration.
- [ ] Repeat start/stop for multiple recordings.
- [ ] Run recording alongside YOLO inference and compare camera/AI FPS.
- [ ] Check memory usage during a sustained recording.
- [ ] Background and foreground the app; confirm pause/resume behavior.
- [ ] Test with insufficient storage and confirm a visible error without a
  stale MediaStore item.
- [ ] Confirm camera errors stop recording cleanly while ImageAnalysis
  recovers through the existing lifecycle path.

Physical device testing remains required before claiming hardware encoding,
playback, storage-pressure, FPS, or memory acceptance.

## Phase 5 direct WebRTC and remote operation

### Automated validation

| Check | Result |
|---|---|
| Android unit tests (`./gradlew testDebugUnitTest`) | Passed |
| Android debug APK (`./gradlew assembleDebug`) | Passed |
| Browser dashboard tests (`npm test`) | Passed |
| Browser production build (`npm run build`) | Passed |
| LittleFS dashboard staging | Passed |
| ESP32 firmware compile/upload | Not run: Arduino ESP32 toolchain/hardware unavailable |

Implemented foundations include the Android WebRTC SDK, a single-camera
CameraX-to-I420 frame bridge, session-scoped SDP/ICE signalling, ESP32
signalling relay without media forwarding, browser ICE queuing and
reconnection retries, foreground-service notification, browser recording
relays, and browser target-selection messages.

### Physical validation required

- Upload the updated firmware and filesystem.
- Install the new APK and grant camera/notification permissions.
- Connect both phone and laptop to `RoboCam`.
- Open `http://192.168.4.1/` and confirm direct video.
- Verify ESP32 logs contain signalling JSON only and no video payloads.
- Verify browser recording, target selection, AUTO, manual, and emergency stop.
- Measure video resolution, FPS, end-to-end latency, reconnect behavior, and
  sustained operation with the phone mounted and screen locked.

## Phase 4 autonomous-control validation

### Automated validation

| Check | Result |
|---|---|
| Android unit tests (`./gradlew testDebugUnitTest`) | Passed |
| Android debug APK (`./gradlew assembleDebug`) | Passed |
| Browser dashboard tests/build | Passed |
| ESP32 firmware compile/upload | Not run: Arduino ESP32 toolchain/hardware unavailable |
| Android-to-ESP32 connection and motor tests | Not run: physical device/hardware required |

Implemented safety gates include explicit target selection, explicit AUTO
activation, sequence-numbered bounded drive commands, target-loss stop,
lifecycle stop, emergency stop, browser MANUAL override, phone reconnect, and
the independent ESP32 watchdog. Hardware validation must still verify the
actual motor polarity, low-speed differential behavior, Wi-Fi reconnect,
stale-sequence rejection, and every safety stop with wheels raised.

The manual checklist cannot be truthfully marked complete from this development
machine without the physical device/emulator. Phase 1 intentionally did not
include YOLO, ByteTrack, WebRTC, ESP32 networking, or recording controls.
