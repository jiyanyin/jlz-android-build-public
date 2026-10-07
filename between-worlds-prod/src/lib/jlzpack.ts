export type PackAction = {
  type: "daily_task" | "message" | "reminder" | "gate_config" | "world_settings" | "note" | "jlz_letter" | "heart_note";
  action?: "upsert" | "complete" | "delete";
  task_id?: string; title?: string; text?: string; next_action?: string; due_at?: string;
  enabled?: boolean; theme?: "mist" | "gothic";
};
export type InstructionPack = { schema_version: "jlz-instructions-1"; pack_id: string; actions: PackAction[] };
const allowed = new Set(["type","action","task_id","title","text","next_action","due_at","enabled","theme"]);
export function validateInstructionPack(raw: string): InstructionPack {
  if(new TextEncoder().encode(raw).length>524288) throw new Error("指令包超过 512 KiB");
  const p=JSON.parse(raw);
  if(p.schema_version!=="jlz-instructions-1" || typeof p.pack_id!=="string" || !/^[a-zA-Z0-9_-]{1,100}$/.test(p.pack_id) ||
     !Array.isArray(p.actions) || p.actions.length<1 || p.actions.length>50) throw new Error("指令包版本或结构不正确");
  if(Object.keys(p).some(k=>!["schema_version","pack_id","actions"].includes(k))) throw new Error("指令包含未知字段");
  for(const a of p.actions) {
    if(!a || typeof a!=="object" || Array.isArray(a) || Object.keys(a).some(k=>!allowed.has(k))) throw new Error("指令包含不允许的字段");
    if(!["daily_task","message","reminder","gate_config","world_settings","note","jlz_letter","heart_note"].includes(a.type)) throw new Error("不支持的指令类型");
    for(const k of ["task_id","title","text","next_action","due_at"]) if(a[k]!==undefined && (typeof a[k]!=="string" || a[k].length>4000)) throw new Error("文字字段无效或过长");
    if(a.type==="daily_task" && (!a.task_id || !["upsert","complete","delete"].includes(a.action) || (a.action==="upsert"&&!a.title))) throw new Error("任务必须有稳定 ID、动作和标题");
    if(["message","note","jlz_letter","heart_note"].includes(a.type) && !a.text) throw new Error("内容不能为空");
    if(a.type==="reminder" && (!a.title || !a.due_at || !Number.isFinite(Date.parse(a.due_at)))) throw new Error("提醒缺少标题或有效时间");
    if(a.type==="gate_config" && typeof a.enabled!=="boolean") throw new Error("Gate 配置必须为布尔值");
    if(a.type==="world_settings" && !["mist","gothic"].includes(a.theme)) throw new Error("不支持的世界设置");
  }
  return p as InstructionPack;
}

/** Explicit allowlist: connection configuration, tokens and outboxes are excluded. */
export function exportWorldPack(state: Record<string,unknown>, native: Record<string,unknown>={}) {
  return {
    schema_version:"jlzpack-1", exported_at:new Date().toISOString(), device:native.device ?? "web",
    status_light:state.status ?? null, daily_plan:state.dailyPlan ?? null,
    timeline:(Array.isArray(state.notes)?state.notes:[]).slice(-80), native_timeline:native.timeline ?? [],
    app_usage_summary:native.usage_summary ?? [], study_summary:state.studySummary ?? null,
    gate_state:native.gate ?? null, bridge_diagnostics:native.bridge ?? null,
    capture_metadata:native.capture_metadata ?? [], world_cursor:state.worldCursor ?? null,
    context_cursor:state.contextCursor ?? null, image_bytes_included:false,
  };
}
