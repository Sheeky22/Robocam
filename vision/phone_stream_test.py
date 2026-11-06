
import cv2
import argparse
from vision_pipeline import RoboCamVision


def main():

    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--url",
        required=True,
        help="Phone MJPEG stream URL, for example https://192.168.1.5:8080/video"
    )

    args = parser.parse_args()

    cap = cv2.VideoCapture(args.url)

    # Keep the stream buffer small to reduce latency.
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    if not cap.isOpened():
        raise RuntimeError(
            "Cannot open video stream. The IP Webcam home URL serves HTML; "
            "use its MJPEG endpoint, usually /video, for example "
            "https://192.168.1.5:8080/video."
        )

    vision = RoboCamVision()

    window = "RoboCam - Phone Live Stream"

    cv2.namedWindow(window)

    def on_mouse(event, x, y, flags, param):

        if event == cv2.EVENT_LBUTTONDOWN:
            vision.request_selection(x, y)

    cv2.setMouseCallback(window, on_mouse)

    print("Phone stream connected")
    print("Click a person to select")
    print("R = Release target")
    print("Q = Quit")

    try:

        while True:

            ret, frame = cap.read()

            if not ret:
                print("Stream frame unavailable")
                break

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
