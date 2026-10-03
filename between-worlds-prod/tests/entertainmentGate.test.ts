import assert from "node:assert/strict";
import test from "node:test";
import { normalizeGateSnapshot } from "../src/lib/entertainmentGate.ts";

test("normalizes native Gate V2 snapshot", () => {
  const snapshot = normalizeGateSnapshot({
    ok: true,
    package_name: "com.xingin.xhs",
    app_name: "小红书",
    tier: "feed",
    device_type: "phone",
    incoming_text: "先接我。",
    purpose_minutes: 5,
    break_minutes: 5,
    direct_minutes: 3,
    small_step_minutes: 5,
    small_step_pending: true,
    small_step_done: false,
    small_step_remaining_ms: 120000,
    small_step_text: "还差两分钟。",
    current_effective_ms: 600000,
  }, "com.xingin.xhs");

  assert.equal(snapshot.ok, true);
  assert.equal(snapshot.app_name, "小红书");
  assert.equal(snapshot.direct_minutes, 3);
  assert.equal(snapshot.small_step_pending, true);
  assert.equal(snapshot.small_step_remaining_ms, 120000);
});

test("uses safe defaults for malformed Gate snapshot", () => {
  const snapshot = normalizeGateSnapshot(null, "com.xingin.xhs");
  assert.equal(snapshot.ok, false);
  assert.equal(snapshot.package_name, "com.xingin.xhs");
  assert.equal(snapshot.purpose_minutes, 5);
  assert.equal(snapshot.direct_minutes, 3);
});
