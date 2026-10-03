import assert from "node:assert/strict";
import test from "node:test";
import {
  emptyStudySummary,
  formatStudyClock,
  liveStudyTotals,
  normalizeStudySummary,
  studyDeviceLabel,
} from "../src/lib/studySession.ts";

test("normalizes Runtime study summary", () => {
  const summary = normalizeStudySummary({
    study: {
      date: "2026-10-03",
      today_effective_ms: 600_000,
      completed_sessions: 2,
      active: true,
      checked_at_ms: 1_000,
      active_sessions: [{
        device_id: "android-phone-native-n0",
        session_id: "s1",
        paused: false,
        started_at_ms: 100,
        active_elapsed_ms: 120_000,
        module: "图推",
        answered_count: 3,
        correct_count: 2,
      }],
    },
  }, "2026-10-03");
  assert.equal(summary.date, "2026-10-03");
  assert.equal(summary.current?.session_id, "s1");
  assert.equal(summary.current?.module, "图推");
  assert.equal(summary.completed_sessions, 2);
});

test("live total advances only running sessions", () => {
  const base = emptyStudySummary("2026-10-03");
  const running = {
    ...base,
    checked_at_ms: 10_000,
    today_effective_ms: 120_000,
    active: true,
    active_sessions: [{
      device_id: "android-phone-native-n0",
      session_id: "s1",
      paused: false,
      started_at_ms: 1_000,
      active_elapsed_ms: 60_000,
      subject: "",
      module: "",
      answered_count: 0,
      correct_count: 0,
      wrong_count: 0,
    }],
  };
  running.current = running.active_sessions[0];
  assert.equal(liveStudyTotals(running, 20_000).todayMs, 130_000);
  assert.equal(liveStudyTotals(running, 20_000).currentMs, 70_000);

  const paused = {
    ...running,
    paused: true,
    active_sessions: [{ ...running.active_sessions[0], paused: true }],
    current: { ...running.active_sessions[0], paused: true },
  };
  assert.equal(liveStudyTotals(paused, 20_000).todayMs, 120_000);
  assert.equal(liveStudyTotals(paused, 20_000).currentMs, 60_000);
});

test("formats study clock and device labels", () => {
  assert.equal(formatStudyClock(3_723_000), "01:02:03");
  assert.equal(studyDeviceLabel("android-tablet-native-n0"), "平板");
  assert.equal(studyDeviceLabel("android-phone-native-n0"), "手机");
});
