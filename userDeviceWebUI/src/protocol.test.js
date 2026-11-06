import { describe, expect, it } from "vitest";
import { commands, KEYBOARD_DRIVE, parseMessage } from "./protocol.js";

describe("protocol", () => {
  it("parses valid JSON messages and rejects malformed messages", () => {
    expect(parseMessage('{"type":"motor_status","status":"stopped"}')).toEqual({ type: "motor_status", status: "stopped" });
    expect(parseMessage("not json").type).toBe("error");
    expect(parseMessage("{}").type).toBe("error");
  });
  it("produces the documented command shapes", () => {
    expect(commands.hello()).toEqual({ type: "hello", role: "web" });
    expect(commands.drive(100, 0)).toEqual({ type: "drive", left: 100, right: 0 });
    expect(commands.camera("START_RECORD")).toEqual({ type: "camera", action: "START_RECORD" });
  });
  it("maps keyboard arrows to robot movement", () => {
    expect(KEYBOARD_DRIVE.ArrowUp).toEqual([100, 100]);
    expect(KEYBOARD_DRIVE.ArrowDown).toEqual([-100, -100]);
    expect(KEYBOARD_DRIVE.ArrowLeft).toEqual([0, -100]);
    expect(KEYBOARD_DRIVE.ArrowRight).toEqual([0, 100]);
    expect(commands.targetSelect(0.25, 0.75)).toEqual({ type: "target_select", x: 0.25, y: 0.75 });
  });
});
