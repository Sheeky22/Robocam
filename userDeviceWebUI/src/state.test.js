import { describe, expect, it } from "vitest";
import { createInitialState, markConnection, reduceState } from "./state.js";

describe("state", () => {
  it("only reflects confirmed control mode messages", () => {
    const initial = createInitialState();
    const requested = { ...initial, requestedMode: "MANUAL" };
    expect(requested.controlMode).toBe("STOPPED");
    expect(reduceState(requested, { type: "control_mode", mode: "MANUAL" }).controlMode).toBe("MANUAL");
  });
  it("stops motor state when ESP32 disconnects", () => {
    const state = { ...createInitialState(), motor: "moving" };
    expect(markConnection(state, "esp32", "disconnected").motor).toBe("stopped");
  });
  it("tracks recording confirmations and clears elapsed state on stop", () => {
    const recording = reduceState(createInitialState(), { type: "recording_status", status: "recording" });
    expect(recording.recordingStartedAt).toEqual(expect.any(Number));
    expect(reduceState(recording, { type: "recording_status", status: "stopped" }).recordingStartedAt).toBeNull();
  });
  it("keeps the confirmed recording state when a command is rejected", () => {
    const recording = reduceState(createInitialState(), { type: "recording_status", status: "recording" });
    const next = reduceState(recording, {
      type: "recording_status",
      status: "error",
      error: "Recording is not paused",
    });
    expect(next.recording).toBe("recording");
    expect(next.error).toBe("Recording is not paused");
  });

  it("does not turn a rejected mode request into an active mode", () => {
    const state = { ...createInitialState(), requestedMode: "AUTO" };
    const next = reduceState(state, { type: "ack", command: "control_mode", accepted: false, error: "Busy" });
    expect(next.controlMode).toBe("STOPPED");
    expect(next.error).toBe("Busy");
  });

  it("surfaces rejected drive commands and emergency motor status", () => {
    const rejected = reduceState(createInitialState(), {
      type: "ack",
      command: "drive",
      accepted: false,
      error: "Select MANUAL mode"
    });
    expect(rejected.error).toBe("Select MANUAL mode");
    const emergency = reduceState(rejected, {
      type: "motor_status",
      status: "stopped",
      mode: "STOPPED",
      emergency: true
    });
    expect(emergency.controlMode).toBe("STOPPED");
    expect(emergency.error).toBe("Emergency stop is active");
  });
});
