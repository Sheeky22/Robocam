import cv2
import argparse
from vision_pipeline import RoboCamVision


def main():

    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--camera",
        type=int,
        default=0
    )

    args = parser.parse_args()

    cap = cv2.VideoCapture(args.camera)

    if not cap.isOpened():
        raise RuntimeError(
            "Cannot open webcam. Check camera permissions and camera index."
        )

    vision = RoboCamVision()

    window = "RoboCam - Laptop Webcam"

    cv2.namedWindow(window)

    def on_mouse(event, x, y, flags, param):

        if event == cv2.EVENT_LBUTTONDOWN:
            vision.request_selection(x, y)

    cv2.setMouseCallback(window, on_mouse)

    print("Webcam test started")
    print("Click a person to select")
    print("R = Release target")
    print("Q = Quit")

    try:

        while True:

            ret, frame = cap.read()

            if not ret:
                print("Failed to read webcam frame")
                break

            # Process using the shared vision pipeline
            output_frame, output = vision.process(frame)

            cv2.imshow(window, output_frame)

            key = cv2.waitKey(1) & 0xFF

            if key == ord("r"):

                vision.release_target()
                print("Target released")

            elif key == ord("q"):
                break

    finally:

        cap.release()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
