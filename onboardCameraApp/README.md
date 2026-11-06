# RoboCam onboard camera

Phase 1 is a device-agnostic Android/Kotlin foundation. It uses CameraX
`Preview` and `ImageAnalysis`, requests camera permission at runtime, keeps only
the latest frame, binds a `VideoCapture` use case without recording controls,
and displays frame metadata and analysis FPS for validation.

The app does not control motors, record video, or implement WebRTC. Galaxy S25
is only one possible test device; the minimum supported Android API is 29.

## Phase 2 vision

Phase 2 adds the exported YOLO11n ONNX model, ONNX Runtime Android inference,
person filtering, bounded ByteTrack association, controller parity logic, and
an on-preview tracking overlay. Model export and the Galaxy S25 benchmark
procedure are documented in [MODEL_EXPORT.md](MODEL_EXPORT.md).

Phase 2A adds the live Vision Performance dashboard, rolling stage latency
statistics, device health sampling, benchmark controls, and CSV export. See
[PERFORMANCE_PHASE2A.md](PERFORMANCE_PHASE2A.md) for runtime limitations and
the controlled Galaxy S25 comparison procedure.

Tap a detected person to select it. The controller also exposes normalized
coordinate selection for future external UI/input sources. ESP32, WebRTC, and
motor commands remain disconnected.

## Phase 3 local recording

The development screen includes local Start recording, Pause / resume, and
Stop recording controls. CameraX `Recorder` writes MP4 output through
`MediaStore` under `Movies/RoboCam`; the device's available CameraX recording
implementation selects hardware encoding where supported. Recording duration,
state, errors, and completed file size are shown in the status overlay.

When the activity backgrounds, an active recording is paused and it resumes
when the activity returns to the foreground. ImageAnalysis and the YOLO
pipeline remain separately bound and continue using the latest-frame strategy.
No ESP32 command is sent by recording controls.

## Phase 4 onboard autonomous control

The Android app can connect directly to `ws://192.168.4.1:81/ws` as the
registered `phone` client. Tap a tracked person in the camera preview, then
explicitly start AUTO. The app sends bounded, sequence-numbered differential
drive commands only while the selected target is visible and vision processing
is healthy. Target loss, lifecycle pause, disconnect, emergency stop, or an
ESP32 rejection disables autonomous movement.

The browser can override phone MANUAL or AUTO at any time. The ESP32
independently rejects stale commands, caps autonomous output, and stops motors
when the command watchdog expires. Phase 5 adds direct browser video and
browser-relayed recording/target/AUTO commands; the ESP32 carries signalling
only, never video media.

Because the ESP32 endpoint is a local unencrypted `ws://` connection, the
Android manifest permits cleartext traffic for this development AP connection.

## Open in Android Studio

1. Open the `onboardCameraApp` folder in Android Studio.
2. Allow Gradle sync to finish and install any SDK components Android Studio
   reports as missing.
3. Select an emulator or a compatible Android phone. The app allows portrait
   and landscape orientation so CameraX rotation metadata can be validated.
4. Run the `app` configuration.

## Test on a physical Android device

1. On the phone, enable Developer options, then USB debugging.
2. Connect the phone by USB and accept the debugging prompt.
3. In Android Studio, select the phone in the device selector and press Run.
4. Accept the camera permission prompt.
5. Confirm the live preview appears and the status panel reports increasing
   frame counts. Rotate the phone and confirm rotation metadata changes.

The instrumentation test requires an emulator or connected device. In Android
Studio, open the **Gradle** tool window and run
`app > Tasks > verification > test` for unit tests or
`app > Tasks > verification > connectedAndroidTest` for device tests. If you
later generate a Gradle wrapper, the equivalent commands are:

```sh
./gradlew test
./gradlew connectedAndroidTest
```

## Build an installable APK

For a test APK, use Android Studio's **Build > Build App Bundle(s) / APK(s) >
Build APK(s)**. Android Studio will show the generated file location. For a
release distributed outside Android Studio, use **Build > Generate Signed App
Bundle / APK**, create or select a private signing key, and keep that key
secure.

## Phase 5 remote operation

The phone starts a foreground service with a persistent notification and keeps
the camera/vision/WebRTC pipeline active while mounted. The browser dashboard
receives direct WebRTC media over the RoboCam Wi-Fi network. Recording controls
and normalized target selection messages are relayed through the ESP32 to the
phone; RTP/RTCP never passes through the ESP32. Host ICE candidates are used
initially, so the browser and phone must be reachable on the same RoboCam
network. WebRTC publishing is capped at 15 FPS to reduce encoding and network
latency; CameraX recording and vision analysis remain separate. Autonomous
movement uses the 100% manual command level as 850 ms pulses, while short target-loss
reacquisition turns are limited to 20%.
