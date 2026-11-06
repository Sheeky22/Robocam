#!/usr/bin/env python3
"""Smoke-test the RoboCam L298N WebSocket contract against a live ESP32."""

import asyncio
import json
import sys

try:
    import websockets
except ImportError:
    print("Install the test dependency with: python3 -m pip install websockets", file=sys.stderr)
    raise SystemExit(2)


URI = "ws://192.168.4.1:81/ws"


async def receive_json(socket):
    return json.loads(await socket.recv())


async def main():
    async with websockets.connect(URI) as socket:
        await socket.send(json.dumps({"type": "drive", "left": 100, "right": 100}))
        response = await receive_json(socket)
        assert response["type"] == "error"
        assert "hello" in response["error"]

        await socket.send(json.dumps({"type": "hello", "role": "web"}))
        response = await receive_json(socket)
        assert response == {
            "type": "ack",
            "command": "hello",
            "accepted": True,
            "role": "web",
        }
        response = await receive_json(socket)
        assert response == {
            "type": "connection",
            "target": "web",
            "state": "connected",
        }

        await socket.send(b"{not-json")
        response = await receive_json(socket)
        assert response == {"type": "error", "error": "Invalid JSON message"}

        await socket.send(json.dumps({"type": "drive", "left": 100, "right": 100}))
        response = await receive_json(socket)
        assert response["type"] == "ack"
        assert response["command"] == "drive"
        assert response["accepted"] is False

        await socket.send(json.dumps({"type": "control_mode", "mode": "MANUAL"}))
        response = await receive_json(socket)
        assert response == {"type": "ack", "command": "control_mode", "accepted": True}
        await receive_json(socket)  # control_mode broadcast
        await receive_json(socket)  # motor_status broadcast

        await socket.send(json.dumps({"type": "drive", "left": 25, "right": -25}))
        response = await receive_json(socket)
        assert response == {"type": "ack", "command": "drive", "accepted": True}
        status = await receive_json(socket)
        assert status["type"] == "motor_status"
        assert status["left"] == 25
        assert status["right"] == -25

        await socket.send(json.dumps({"type": "emergency_stop"}))
        response = await receive_json(socket)
        assert response == {"type": "ack", "command": "emergency_stop", "accepted": True}


if __name__ == "__main__":
    asyncio.run(main())
