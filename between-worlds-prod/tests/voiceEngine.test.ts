import assert from "node:assert/strict";
import test from "node:test";
import {
  EMPTY_VOICE_MEMORY,
  formatVoiceClock,
  selectVoiceCard,
  type VoiceInput,
} from "../src/lib/voiceEngine.ts";

const local = (hour: number, minute = 0, second = 0) => new Date(`2026-10-03T${[hour,minute,second].map(n=>String(n).padStart(2,"0")).join(":")}+08:00`);

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
  assert.match(card.id, /^study-/);
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

test("no-analysis preference chooses a non-analytic affectionate bank", () => {
  const now = local(18, 10);
  const card = selectVoiceCard({
    now,
    status: {
      at: now.toISOString(),
      detail: { "想听我怎样回应": ["先别分析"] },
    },
  }, EMPTY_VOICE_MEMORY);
  assert.equal(card.signal, "style:affection");
});

test("native JLZ mood changes voice without inventing user activity", () => {
  const now = local(20, 10);
  const card = selectVoiceCard({now, nativeMood:"LONGING"}, EMPTY_VOICE_MEMORY);
  assert.equal(card.signal, "jlz:longing");
  assert.match(card.id, /^jlz-long-/);
  assert.notEqual(card.signal, "activity:study");
});
test("an explicit user quiet preference takes priority over native mood", () => {
  const now = local(20, 10);
  const card = selectVoiceCard({now,nativeMood:"FOCUSED",status:{
    at: now.toISOString(), detail: {"想听我怎样回应":["现在先不用回复"]}
  }}, EMPTY_VOICE_MEMORY);
  assert.equal(card.signal, "style:silent");
});
test("local study still takes priority over companion's evening mood", () => {
  const now = local(20, 10);
  const card = selectVoiceCard({
    now,nativeMood:"LONGING",activeLife:{action:"刷题",startAt:now.getTime()-15*60_000}
  }, EMPTY_VOICE_MEMORY);
  assert.equal(card.signal, "activity:study");
});
