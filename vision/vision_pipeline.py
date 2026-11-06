import cv2
import time
from ultralytics import YOLO


class RoboCamVision:

    def __init__(
        self,
        model_path="models/yolo11n.pt",
        imgsz=640,
        confidence=0.35,
        alpha=0.25
    ):

        self.model = YOLO(model_path)

        self.imgsz = imgsz
        self.confidence = confidence
        self.alpha = alpha

        # Controller thresholds
        self.horizontal_start = 0.05
        self.horizontal_stop = 0.025

        self.scale_start = 0.08
        self.scale_stop = 0.04

        # Target state
        self.selected_id = None
        self.reference_height = None

        # Current detections
        self.boxes = []
        self.ids = []

        # Mouse click is processed in the main loop
        self.pending_click = None

        # Smoothed errors
        self.smooth_x = None
        self.smooth_scale = None

        # Movement decisions
        self.rotation_state = "STOP"
        self.distance_state = "STOP"

        # Performance
        self.previous_time = time.perf_counter()
        self.fps = 0.0
        self.inference_ms = 0.0

    # ----------------------------------
    # RESET CONTROLLER
    # ----------------------------------

    def reset_controller(self):

        self.smooth_x = None
        self.smooth_scale = None

        self.rotation_state = "STOP"
        self.distance_state = "STOP"

    # ----------------------------------
    # TARGET SELECTION
    # ----------------------------------

    def request_selection(self, x, y):

        self.pending_click = (x, y)

    def release_target(self):

        self.selected_id = None
        self.reference_height = None

        self.pending_click = None

        self.reset_controller()

    def process_selection(self, frame_height):

        if self.pending_click is None:
            return

        click_x, click_y = self.pending_click
        self.pending_click = None

        for box, track_id in zip(self.boxes, self.ids):

            x1, y1, x2, y2 = box

            if x1 <= click_x <= x2 and y1 <= click_y <= y2:

                box_height = y2 - y1

                if box_height <= 0:
                    return

                self.selected_id = track_id

                self.reference_height = (
                    box_height / frame_height
                )

                self.reset_controller()

                print("\nTarget selected")
                print("Tracking ID:", track_id)
                print(
                    "Reference height:",
                    round(self.reference_height, 4)
                )

                return

    # ----------------------------------
    # EMA
    # ----------------------------------

    def smooth(self, raw, previous):

        if previous is None:
            return raw

        return (
            self.alpha * raw
            + (1 - self.alpha) * previous
        )

    # ----------------------------------
    # HORIZONTAL HYSTERESIS
    # ----------------------------------

    def update_rotation(self, error):

        state = self.rotation_state

        if state == "STOP":

            if error < -self.horizontal_start:
                state = "LEFT"

            elif error > self.horizontal_start:
                state = "RIGHT"

        elif state == "LEFT":

            if error > self.horizontal_start:
                state = "RIGHT"

            elif error > -self.horizontal_stop:
                state = "STOP"

        elif state == "RIGHT":

            if error < -self.horizontal_start:
                state = "LEFT"

            elif error < self.horizontal_stop:
                state = "STOP"

        self.rotation_state = state

    # ----------------------------------
    # DISTANCE HYSTERESIS
    # ----------------------------------

    def update_distance(self, error):

        state = self.distance_state

        if state == "STOP":

            if error < -self.scale_start:
                state = "FORWARD"

            elif error > self.scale_start:
                state = "BACKWARD"

        elif state == "FORWARD":

            if error > self.scale_start:
                state = "BACKWARD"

            elif error > -self.scale_stop:
                state = "STOP"

        elif state == "BACKWARD":

            if error < -self.scale_start:
                state = "FORWARD"

            elif error < self.scale_stop:
                state = "STOP"

        self.distance_state = state

    # ----------------------------------
    # TARGET-LOSS HANDLING
    # ----------------------------------

    def target_lost(self):

        self.rotation_state = "STOP"
        self.distance_state = "STOP"

        self.smooth_x = None
        self.smooth_scale = None

    # ----------------------------------
    # MAIN VISION PIPELINE
    # ----------------------------------

    def process(self, frame):

        frame_height, frame_width = frame.shape[:2]

        start_time = time.perf_counter()

        results = self.model.track(
            frame,
            persist=True,
            classes=[0],
            tracker="bytetrack.yaml",
            conf=self.confidence,
            imgsz=self.imgsz,
            verbose=False
        )

        self.inference_ms = (
            time.perf_counter() - start_time
        ) * 1000

        self.boxes = []
        self.ids = []

        result = results[0]

        if result.boxes is not None and result.boxes.id is not None:

            xyxy = result.boxes.xyxy.cpu().numpy()
            track_ids = result.boxes.id.cpu().numpy().astype(int)

            for box, track_id in zip(xyxy, track_ids):

                x1, y1, x2, y2 = box.astype(int)

                self.boxes.append(
                    (x1, y1, x2, y2)
                )

                self.ids.append(int(track_id))

        # Process mouse selection against current detections
        self.process_selection(frame_height)

        frame_center_x = frame_width / 2

        cv2.line(
            frame,
            (int(frame_center_x), 0),
            (int(frame_center_x), frame_height),
            (255, 255, 0),
            2
        )

        selected_detected = False

        raw_x = None
        raw_scale = None

        for box, track_id in zip(self.boxes, self.ids):

            x1, y1, x2, y2 = box

            if track_id == self.selected_id:

                selected_detected = True

                # Horizontal error relative to frame centre
                current_center_x = (x1 + x2) / 2

                raw_x = (
                    current_center_x - frame_center_x
                ) / frame_center_x

                # Apparent subject size
                current_height = (
                    y2 - y1
                ) / frame_height

                if (
                    self.reference_height is not None
                    and self.reference_height > 0
                    and current_height > 0
                ):

                    raw_scale = (
                        current_height / self.reference_height
                    ) - 1

                    self.smooth_x = self.smooth(
                        raw_x,
                        self.smooth_x
                    )

                    self.smooth_scale = self.smooth(
                        raw_scale,
                        self.smooth_scale
                    )

                    self.update_rotation(self.smooth_x)
                    self.update_distance(self.smooth_scale)

                cv2.rectangle(
                    frame,
                    (x1, y1),
                    (x2, y2),
                    (0, 0, 255),
                    3
                )

                cv2.putText(
                    frame,
                    f"SELECTED ID: {track_id}",
                    (x1, max(20, y1 - 10)),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.7,
                    (0, 0, 255),
                    2
                )

            else:

                cv2.rectangle(
                    frame,
                    (x1, y1),
                    (x2, y2),
                    (0, 255, 0),
                    2
                )

                cv2.putText(
                    frame,
                    f"ID: {track_id}",
                    (x1, max(20, y1 - 10)),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    (0, 255, 0),
                    2
                )

        # Stop immediately when target is not detected
        if self.selected_id is not None and not selected_detected:
            self.target_lost()

        # FPS measurement
        current_time = time.perf_counter()
        delta = current_time - self.previous_time

        if delta > 0:
            instant_fps = 1.0 / delta
            self.fps = (
                0.9 * self.fps + 0.1 * instant_fps
                if self.fps > 0 else instant_fps
            )

        self.previous_time = current_time

        # Display metrics
        y = 30

        display_values = [
            f"FPS: {self.fps:.1f}",
            f"Inference: {self.inference_ms:.1f} ms",
            f"Rotation: {self.rotation_state}",
            f"Distance: {self.distance_state}"
        ]

        if self.selected_id is not None:

            display_values.extend([
                f"Raw X: {raw_x if raw_x is not None else 'N/A'}",
                f"Smooth X: {self.smooth_x if self.smooth_x is not None else 'N/A'}",
                f"Raw Scale: {raw_scale if raw_scale is not None else 'N/A'}",
                f"Smooth Scale: {self.smooth_scale if self.smooth_scale is not None else 'N/A'}"
            ])

        for value in display_values:

            cv2.putText(
                frame,
                value,
                (10, y),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.55,
                (255, 255, 255),
                2
            )

            y += 25

        if self.selected_id is None:

            message = "Click on a person to select"

            cv2.putText(
                frame,
                message,
                (10, frame_height - 20),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.7,
                (255, 255, 255),
                2
            )

        elif not selected_detected:

            cv2.putText(
                frame,
                "TARGET LOST - MOVEMENT STOPPED",
                (10, frame_height - 20),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.7,
                (0, 0, 255),
                2
            )

        # Structured output for future ESP32 integration
        output = {
            "target_detected": selected_detected,
            "target_id": self.selected_id,
            "horizontal_error": self.smooth_x,
            "scale_error": self.smooth_scale,
            "rotation": self.rotation_state,
            "distance": self.distance_state,
            "fps": self.fps,
            "inference_ms": self.inference_ms
        }

        return frame, output
