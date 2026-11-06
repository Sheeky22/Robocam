import json
from pathlib import Path

from vision_pipeline import RoboCamVision


def run_fixture() -> list[dict]:
    fixture = json.loads(
        (Path(__file__).parent / "controller_parity_fixture.json").read_text()
    )
    vision = RoboCamVision.__new__(RoboCamVision)
    vision.alpha = fixture["alpha"]
    vision.horizontal_start = 0.05
    vision.horizontal_stop = 0.025
    vision.scale_start = 0.08
    vision.scale_stop = 0.04
    vision.selected_id = 7
    vision.reference_height = 0.4
    vision.smooth_x = None
    vision.smooth_scale = None
    vision.rotation_state = "STOP"
    vision.distance_state = "STOP"
    output = []
    for frame in fixture["sequence"]:
        box = (
            frame["center_x"] - 100,
            100,
            frame["center_x"] + 100,
            100 + frame["height"],
        )
        raw_x = (frame["center_x"] - fixture["frame_width"] / 2) / (
            fixture["frame_width"] / 2
        )
        raw_scale = (frame["height"] / fixture["frame_height"]) / vision.reference_height - 1
        vision.smooth_x = vision.smooth(raw_x, vision.smooth_x)
        vision.smooth_scale = vision.smooth(raw_scale, vision.smooth_scale)
        vision.update_rotation(vision.smooth_x)
        vision.update_distance(vision.smooth_scale)
        output.append(
            {
                "box": box,
                "horizontal_error": vision.smooth_x,
                "scale_error": vision.smooth_scale,
                "rotation": vision.rotation_state,
                "distance": vision.distance_state,
            }
        )
    return output


def test_controller_parity_fixture():
    fixture = json.loads(
        (Path(__file__).parent / "controller_parity_fixture.json").read_text()
    )
    actual = run_fixture()
    expected = fixture["expected"]
    for index, frame in enumerate(actual):
        assert abs(frame["horizontal_error"] - expected["horizontal_errors"][index]) < 1e-6
        assert abs(frame["scale_error"] - expected["scale_errors"][index]) < 1e-6
        assert frame["rotation"] == expected["rotation"][index]
        assert frame["distance"] == expected["distance"][index]
