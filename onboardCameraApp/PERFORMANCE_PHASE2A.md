# Phase 2A performance status

## Changes implemented

- Added rolling 600-sample statistics (approximately the latest 30-60 seconds
  depending on workload) for image conversion, preprocessing, YOLO inference,
  postprocessing/NMS, ByteTrack, controller, and end-to-end latency.
- Added separate camera-capture FPS and AI-processing FPS.
- Added received, processed, skipped/dropped frames, target-loss events, people,
  memory PSS, `/proc/stat` CPU estimate, and Android thermal status.
- Added a live Vision Performance dashboard with Start Benchmark, Stop / Export
  CSV, and Reset Stats controls.
- Added low-overhead session CSV export; samples are aggregated in memory and
  are not posted to the UI for every frame.
- Moved the busy-frame gate before RGB conversion. Stale frames are discarded
  before conversion, and `ImageProxy` remains lifecycle-owned and is closed by
  the analyzer.
- Recycles temporary bitmaps after processing.
- The validated ByteTrack and controller parameters/math were not changed.

## Runtime verification

The current dependency is the standard `onnxruntime-android:1.20.0` artifact.
The dashboard reports this as CPU because no QNN or NNAPI execution provider is
registered. It would be incorrect to claim NPU acceleration from this APK.

QNN requires a compatible ONNX Runtime/QNN build, Qualcomm AI Engine Direct
(QAIRT/QNN) libraries, provider registration, and device-side verification.
The official starting points are:

- https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
- https://onnxruntime.ai/docs/build/android.html
- https://github.com/onnxruntime/onnxruntime-qnn

This project does not include those proprietary/device-specific libraries, so
QNN is documented as not active rather than silently falling back to CPU.
NNAPI is also not enabled. The dashboard therefore reports:

```text
Inference runtime: ONNX Runtime Android 1.20.0
Execution provider: CPU (QNN not enabled)
Accelerator: not active
CPU fallback: unknown
```

## Controlled comparison status

| Configuration | Status | Result |
|---|---|---|
| Existing Phase 2 baseline, 640 input | Previously measured on Galaxy S25 by user | Approximately 5 FPS, approximately 500 ms inference |
| Optimised frame gate, 320 input, CPU ORT | Implemented and build-verified | Requires Galaxy S25 run; no device result claimed |
| 320 input local Apple Silicon ONNX benchmark | Executed | 6.66 ms median, 24.93 ms p95, 81.69 FPS mean |
| QNN/NPU | Not executed | Requires QNN/QAIRT Android runtime and Galaxy device |
| FP16/INT8 | Not adopted | Requires representative calibration and quality comparison |

The local result is not a phone result and must not be used as the Galaxy S25
acceptance result. Use the dashboard CSV for the actual device comparison.

## Galaxy S25 procedure

For each configuration, use the same scene and repeat for at least 60 seconds
after warm-up:

1. Install the APK and grant camera permission.
2. Press **Reset Stats**, then **Start Benchmark**.
3. Keep the same people/scene, distance, lighting, and orientation.
4. Monitor camera FPS, AI FPS, stage averages, P50/P95 end-to-end latency,
   skipped frames, memory, CPU, and thermal status.
5. Press **Stop / Export CSV** and save the session.
6. Repeat for 640, 416, and 320 model assets when available, and for any
   verified QNN/FP16/INT8 build.

Detection quality must be recorded with each CSV: people detected, missed
persons, selected-target continuity, ID switches, target-loss events, and
occlusion/far-target behavior. No configuration should be accepted solely for
higher FPS if tracking quality degrades materially.
