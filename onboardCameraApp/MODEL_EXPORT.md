# YOLO11n Android model

Phase 2 uses ONNX Runtime Android with a fixed 320x320 YOLO11n ONNX model.
The checked-in asset is `app/src/main/assets/yolo11n.onnx`; it is generated
from the repository's `vision/models/yolo11n.pt`.

## Reproduce the export

From the repository root, install the Python vision dependencies and export:

```sh
python3 -m pip install -r vision/requirements.txt onnx
python3 - <<'PY'
from ultralytics import YOLO
model = YOLO("vision/models/yolo11n.pt")
model.export(format="onnx", imgsz=320, simplify=False, dynamic=False)
PY
cp vision/models/yolo11n.onnx onboardCameraApp/app/src/main/assets/yolo11n.onnx
```

The export used for Phase 2 produced a 320x320, opset 18, static-shape model
with output shape `(1, 84, 2100)`. The Android adapter converts camera RGB
pixels to NCHW float values in `[0, 1]`, decodes YOLO xywh predictions, keeps
class `0` (person), applies confidence filtering and NMS, then maps boxes back
to the camera frame dimensions.

The ONNX runtime dependency is `onnxruntime-android:1.20.0`. The app uses the
runtime only for model inference; ByteTrack, coordinate conversion, and the
controller are independent Kotlin components.

## Benchmark procedure

The live benchmark must be run on the Galaxy S25 and repeated on another
compatible Android device:

1. Install the debug APK and grant camera permission.
2. Aim at one or more people under normal lighting.
3. Record the overlay's inference milliseconds and processing FPS for 60
   seconds after warm-up.
4. Record min, median, p95 inference latency, average processing FPS, and
   whether frames are dropped or the UI becomes thermally unstable.
5. Repeat with portrait and landscape orientation.

No Galaxy S25 latency result is claimed until this procedure is run on the
physical device. The 320 export completed locally in 0.3 seconds and produced
a 10.1 MB model; that is an export measurement, not an Android inference
benchmark. A separate local Apple Silicon CPU run of the 320 asset measured
6.66 ms median, 24.93 ms p95, and 81.69 FPS mean after warm-up; this is also
not a Galaxy S25 result.
