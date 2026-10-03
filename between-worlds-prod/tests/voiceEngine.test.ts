import assert from "node:assert/strict";
import test from "node:test";
import {
  EMPTY_VOICE_MEMORY,
  formatVoiceClock,
  selectVoiceCard,
  type VoiceInput,
} from "../src/lib/voiceEngine.ts";

const at = (iso: string) => new Date(iso);

test("clock includes seconds", () => {
  assert.equal(formatVoiceClock(at("2026-10-03T18:07:09+08:00")), "18:07:09");
});

test("same context slot keeps the same line", () => {
  const input: VoiceInput = { now: at("2026-10-03T18:10:00+08:00") };
  const first = selectVoiceCard(input, EMPTY_VOICE_MEMORY);
  const second = selectVoiceCard({ now: at("2026-10-03T18:20:00+08:00") }, first.nextMemory);
  assert.equal(second.id, first.id);
  assert.equal(second.contextKey, first.contextKey);
});

test("next slot rotates away from a recently used line", () => {
  const first = selectVoiceCard({ now: at("2026-10-03T18:10:00+08:00") }, EMPTY_VOICE_MEMORY);
  const second = selectVoiceCard({ now: at("2026-10-03T18:40:00+08:00") }, first.nextMemory);
  assert.notEqual(second.id, first.id);
});

test("active study overrides ordinary daypart", () => {
  const card = selectVoiceCard({
    now: at("2026-10-03T18:10:00+08:00"),
    activeLife: { action: "刷题", startAt: at("2026-10-03T17:55:00+08:00").getTime() },
  }, EMPTY_VOICE_MEMORY);
  assert.equal(card.signal, "activity:study");
  assert.match(card.body, /刷题|分钟|做题|当前|题/);
});

test("explicit quiet-response preference has highest priority", () => {
  const now = at("2026-10-03T18:10:00+08:00");
  const card = selectVoiceCard({
    now,
    activeLife: { action: "刷题", startAt: now.getTime() - 15 * 60 * 1000 },
    status: {
      at: now.toISOString(),
      detail: { "想听我怎样回应": ["现在先不用回复"] },
    },
  }, EMPTY_VOICE_MEMORY);
  assert.equal(card.signal, "style:silent");
});

test("fresh distress state overrides activity", () => {
  const now = at("2026-10-03T18:10:00+08:00");
  const card = selectVoiceCard({
    now,
    activeLife: { action: "工作", startAt: now.getTime() - 20 * 60 * 1000 },
    status: {
      at: now.toISOString(),
      axes: { agitation: 85 },
      detail: { "我能辨认出的情绪": ["烦躁"] },
    },
  }, EMPTY_VOICE_MEMORY);
  assert.equal(card.signal, "state:distress");
});

test("stale status is ignored", () => {
  const now = at("2026-10-03T18:10:00+08:00");
  const card = selectVoiceCard({
    now,
    activeLife: { action: "工作", startAt: now.getTime() - 20 * 60 * 1000 },
    status: {
      at: at("2026-10-03T08:00:00+08:00").toISOString(),
      axes: { agitation: 90 },
    },
  }, EMPTY_VOICE_MEMORY);
  assert.equal(card.signal, "activity:work");
});
