# Phase 2 test report

## Automated results

| Check | Result |
|---|---|
| Kotlin unit tests (`./gradlew testDebugUnitTest`) | Passed |
| Python controller parity test | Passed |
| Debug APK (`./gradlew assembleDebug`) | Passed |
| YOLO11n export | Passed: 640x640, opset 18, 10.2 MB ONNX |
| Local ONNX CPU benchmark | Passed for 320 model: median 6.66 ms, p95 24.93 ms, 81.69 FPS mean |
| Galaxy S25 live benchmark | Pending physical-device run |
| Android instrumentation test | Pending emulator/device run |

The Kotlin controller tests cover the Python fixture's EMA and hysteresis
sequence, target-loss stop behavior, normalized selection, and ByteTrack ID
stability. The Python and Kotlin implementations use the same fixture at
`vision/controller_parity_fixture.json`.

## Required Galaxy S25 validation

1. Install `app/build/outputs/apk/debug/app-debug.apk`.
2. Grant camera permission and aim at people.
3. Tap a person to select it.
4. Verify green boxes/IDs, red selected box, center line, errors, rotation and
   distance decisions, inference latency, and processing FPS.
5. Record a 60-second warm-up plus benchmark window and calculate min, median,
   and p95 inference latency and average processing FPS.
6. Repeat in portrait and landscape, then background/foreground the app.
7. Check Android Studio Memory Profiler for continuous growth and confirm the
   analyzer remains bounded.

No Galaxy S25 result is claimed until these steps are run on the device.
