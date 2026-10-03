import assert from "node:assert/strict";
import test from "node:test";
import {
  liveNativeStudy,
  normalizeNativeStudySnapshot,
} from "../src/lib/nativeStudy.ts";

test("normalizes native study snapshot", () => {
  const snapshot = normalizeNativeStudySnapshot({
    version: "study-bridge-2",
    captured_at_ms: 1_000,
    active: true,
    paused: false,
    session_id: "s1",
    current_ms: 60_000,
    today_ms: 120_000,
    completed_sessions: 2,
    stale_recovered: false,
  });
  assert.equal(snapshot.active, true);
  assert.equal(snapshot.current_ms, 60_000);
  assert.equal(snapshot.completed_sessions, 2);
});

test("live study advances only while active and unpaused", () => {
  const running = normalizeNativeStudySnapshot({
    captured_at_ms: 1_000,
    active: true,
    paused: false,
    current_ms: 30_000,
    today_ms: 90_000,
  });
  assert.deepEqual(
    liveNativeStudy(running, 11_000),
    { currentMs: 40_000, todayMs: 100_000 },
  );

  const paused = { ...running, paused: true };
  assert.deepEqual(
    liveNativeStudy(paused, 11_000),
    { currentMs: 30_000, todayMs: 90_000 },
  );
});
