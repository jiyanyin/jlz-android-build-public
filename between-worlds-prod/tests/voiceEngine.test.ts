import assert from "node:assert/strict";
import test from "node:test";
import {
  EMPTY_VOICE_MEMORY,
  formatVoiceClock,
  selectVoiceCard,
  type VoiceInput,
} from "../src/lib/voiceEngine.ts";

const local = (hour: number, minute = 0, second = 0) => new Date(2026, 9, 3, hour, minute, second, 0);

test("clock includes seconds", () => {
  assert.equal(formatVoiceClock(local(18, 7, 9)), "18:07:09");
});

test("same context slot keeps the same line", () => {
  const input: VoiceInput = { now: local(18, 10) };
  const first = selectVoiceCard(input, EMPTY_VOICE_MEMORY);
  const second = selectVoiceCard({ now: local(18, 20) }, first.nextMemory);
  assert.equal(second.id, first.id);
  assert.equal(second.contextKey, first.contextKey);
});

test("next slot rotates away from a recently used line", () => {
  const first = selectVoiceCard({ now: local(18, 10) }, EMPTY_VOICE_MEMORY);
  const second = selectVoiceCard({ now: local(18, 40) }, first.nextMemory);
  assert.notEqual(second.id, first.id);
});

test("active study overrides ordinary daypart", () => {
  const card = selectVoiceCard({
    now: local(18, 10),
    activeLife: { action: "刷题", startAt: local(17, 55).getTime() },
  }, EMPTY_VOICE_MEMORY);
  assert.equal(card.signal, "activity:study");
  assert.match(card.body, /刷题|分钟|做题|当前|题/);
});

test("explicit quiet-response preference has highest priority", () => {
  const now = local(18, 10);
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
  const now = local(18, 10);
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
  const now = local(18, 10);
  const card = selectVoiceCard({
    now,
    activeLife: { action: "工作", startAt: now.getTime() - 20 * 60 * 1000 },
    status: {
      at: local(8, 0).toISOString(),
      axes: { agitation: 90 },
    },
  }, EMPTY_VOICE_MEMORY);
  assert.equal(card.signal, "activity:work");
});
