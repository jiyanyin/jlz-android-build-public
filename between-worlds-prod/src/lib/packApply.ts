import { emptyDailyPlan, optimisticTaskMutation, type DailyPlan } from "./dailyPlan.ts";
import type { InstructionPack } from "./jlzpack";
import type { OutboxItem, RemoteMessage } from "./runtime";

export type PackState = {
  theme: "mist" | "gothic" | "deepsea" | "rose";
  dailyPlan: DailyPlan | null;
  outbox: OutboxItem[];
  notes: {id: number | string; type: string; at: string; body: string}[];
  importedMessages: RemoteMessage[];
  packAudit: {id: string; at: string; count: number}[];
  pendingPackGate: boolean | null;
};

/** Pure staging: caller persists the entire result before publishing or sending it. */
export function stageInstructionPack<T extends PackState>(state: T, pack: InstructionPack, date: string, at: string): T {
  if (state.packAudit.some(e => e.id === pack.pack_id)) throw new Error("这个包已经应用过。");
  let next = {...state, outbox: [...state.outbox], notes: [...state.notes], importedMessages: [...state.importedMessages]};
  for (const [index, a] of pack.actions.entries()) {
    const id = `pack-${pack.pack_id}-${index}`;
    if (a.type === "daily_task" || a.type === "reminder") {
      const action = a.type === "reminder" ? "upsert" : a.action!;
      const task = {task_id: a.task_id || id, title: a.title, next_action: a.next_action, due_at: a.due_at};
      next.dailyPlan = optimisticTaskMutation(next.dailyPlan?.date === date ? next.dailyPlan : emptyDailyPlan(date), date, action, task);
      next.outbox.push({event_id: id, path: "/api/web/daily-plan/task", body: {date, action, task, client_at: at}, queuedAt: at, tries: 0});
    } else if (a.type === "gate_config") next.pendingPackGate = a.enabled!;
    else if (a.type === "world_settings") next.theme = a.theme!;
    else if (a.type === "message") next.importedMessages.push({id, text: a.text!, at, fromCompanion: true});
    else next.notes.unshift({id, type: a.type, at, body: a.text!});
  }
  if (next.outbox.length > 300) throw new Error("待同步队列已满，请联网同步后再导入。");
  next.packAudit = [...state.packAudit, {id: pack.pack_id, at, count: pack.actions.length}];
  return next;
}
