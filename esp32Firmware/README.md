# RoboCam ESP32 firmware

This directory contains the ESP32 firmware for the DevKit V1. It provides the
RoboCam Wi-Fi access point, HTTP dashboard, WebSocket server, L298N motor
control, role tracking, drive watchdog, emergency stop, and serial diagnostics.
The Android phone can additionally request bounded AUTO control over WebSocket.

## Arduino IDE setup

1. Install the Espressif `esp32` board package and select **ESP32 Dev Module**.
2. Install these libraries through Library Manager:
   - ArduinoJson 6.x or 7.x
   - WebSockets by Markus Sattler
3. Build and stage the dashboard filesystem:

   ```sh
   cd userDeviceWebUI
   npm run build
   cd ../esp32Firmware
   ./prepare_littlefs.sh
   ```

4. Install an Arduino IDE LittleFS data uploader compatible with your Arduino
   IDE version. The `RoboCamFirmware/data/` directory is beside
   `RoboCamFirmware.ino`.
5. In Arduino IDE, open `RoboCamFirmware.ino`, select the ESP32 board and the
   correct serial port, then upload the sketch.
6. Upload the `data/` directory using the LittleFS uploader. This is a
   separate filesystem upload; uploading the sketch alone does not upload the
   dashboard.
7. Open Serial Monitor at 115200 baud.
8. Connect a laptop or phone to Wi-Fi network `RoboCam` using password
   `robocam123`.
9. Browse to `http://192.168.4.1/`.

The WebSocket endpoint is `ws://192.168.4.1:81/ws`. The sketch uses the ESP32
core's built-in `WiFi`, `WebServer`, and `LittleFS` libraries.

## Dashboard deployment

Build the existing dashboard with:

```sh
cd userDeviceWebUI
npm install
npm run build
```

The firmware serves `index.html` and its `/assets/` files from LittleFS.
Without an `index.html`, the HTTP server returns a plain firmware status
response.

## Manual WebSocket test

Install the host test dependency and run it while connected to the RoboCam AP:

```sh
python3 -m pip install websockets
python3 esp32Firmware/test_websocket.py
```

The test checks registration, connection events, pre-registration rejection,
malformed JSON rejection, and command acknowledgements. Keep the wheels off
the ground for physical testing.

To check the HTTP server before opening the dashboard:

```sh
curl -i http://192.168.4.1/
curl -i http://192.168.4.1/assets/<asset-name-from-index.html>
```

The second request should return `200` with JavaScript or CSS content.

## Wiring reference

See [protocol.md](protocol.md) for the validated L298N pin mapping, command
contract, watchdog, and power requirements.

The dashboard refreshes the active `drive` command while a movement button or
arrow key is held. Releasing the control, losing browser focus, disconnecting,
or waiting beyond the firmware watchdog timeout stops the motors. Arrow keys
map to Up=forward, Down=backward, Left=left rotation, and Right=right
rotation.
