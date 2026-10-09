import { nativeRequest } from "./nativeTransport";
import type { WorldContent } from "./worldContent";
import { normalizeDailyPlan, type DailyPlan } from "./dailyPlan";
import { normalizeStudySummary, type StudySummary } from "./studySession";
// Low-privilege Web client for the World Between Runtime.
// Only ever sends the user-entered Web token (X-Web-Token). Never handles Android/MCP credentials.

export const DEFAULT_RUNTIME_URL = "https://jlz-palm-server.onrender.com";
export const SPACE_ID = "world-between-primary";

export type RuntimeConfig = { baseUrl: string; token: string };
export type HomeBridge = {
  available(): boolean;
  request(path: string, method: string, body: string): string;
  requestAsync?(id: string, path: string, method: string, body: string): void;
  snapshot(): string;
  savePack(raw: string): void;
  openPack(): void;
  setGateEnabled(value: boolean): void;
};
export const homeBridge = () => (window as unknown as {WorldBetweenHome?: HomeBridge}).WorldBetweenHome;
export type WritePath =
  | "/api/web/status"
  | "/api/web/moment"
  | "/api/web/life/action"
  | "/api/web/journal"
  | "/api/web/message"
  | "/api/web/presence"
  | "/api/web/daily-plan/task";
export type OutboxItem = { event_id: string; path: WritePath; body: Record<string, unknown>; queuedAt: string; tries: number; rejected?: boolean; error?: string };

export class RuntimeError extends Error {
  constructor(message: string, public status: number) { super(message); }
  /** Client errors (other than timeout/rate-limit/auth) will never succeed on retry. */
  get permanent() { return this.status >= 400 && this.status < 500 && ![401, 403, 408, 425, 429].includes(this.status); }
}

export const newEventId = () =>
  `web-${Date.now().toString(36)}-${(globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2)).slice(0, 12)}`;

const base = (cfg: RuntimeConfig) => (cfg.baseUrl.trim() || DEFAULT_RUNTIME_URL).replace(/\/+$/, "");

async function request<T>(cfg: RuntimeConfig, path: string, init: { method?: "GET" | "POST"; body?: unknown } = {}): Promise<T> {
  const native = homeBridge();
  if(native?.available()) {
    const reply=JSON.parse(await nativeRequest(native,path,init.method ?? "GET",JSON.stringify(init.body ?? {})));
    if(reply.status<200 || reply.status>=300) throw new RuntimeError("Home Node unavailable",reply.status);
    return JSON.parse(reply.body || "{}");
  }
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 15000);
  try {
    const res = await fetch(base(cfg) + path, {
      method: init.method ?? "GET",
      headers: { "X-Web-Token": cfg.token, ...(init.body ? { "Content-Type": "application/json" } : {}) },
      body: init.body ? JSON.stringify(init.body) : undefined,
      signal: ctrl.signal,
      cache: "no-store",
    });
    if (!res.ok) throw new RuntimeError(`Runtime ${res.status}`, res.status);
    const text = await res.text();
    return (text ? JSON.parse(text) : {}) as T;
  } catch (e) {
    if (e instanceof RuntimeError) throw e;
    throw new RuntimeError(e instanceof Error ? e.message : "network", 0);
  } finally {
    clearTimeout(timer);
  }
}

const q = `space_id=${encodeURIComponent(SPACE_ID)}`;
export const runtime = {
  worldContent: (cfg: RuntimeConfig, scope: string) => request<{content:WorldContent}>(cfg, `/api/web/world-content?${q}&device_scope=${encodeURIComponent(scope)}`),
  changeContent: (cfg:RuntimeConfig, body:Record<string,unknown>, rollback=false) => request<{content:WorldContent}>(cfg, `/api/web/world-content${rollback?'/rollback':''}`, {method:'POST',body:{space_id:SPACE_ID,...body}}),
  contentReceipt: (cfg:RuntimeConfig, body:Record<string,unknown>) => request(cfg, '/api/web/world-content/receipt', {method:'POST',body:{space_id:SPACE_ID,...body}}),
  exportContext: (cfg: RuntimeConfig) => request<{context_pack: Record<string,unknown>}>(cfg, `/api/web/export-context?${q}`),
  health: (cfg: RuntimeConfig) => request<Record<string, unknown>>(cfg, "/api/web/health"),
  state: (cfg: RuntimeConfig) => request<Record<string, unknown>>(cfg, `/api/web/state?${q}`),
  messages: (cfg: RuntimeConfig, limit = 80) => request<unknown>(cfg, `/api/web/messages?${q}&limit=${limit}`),
  dailyPlan: async (cfg: RuntimeConfig, date: string): Promise<DailyPlan> =>
    normalizeDailyPlan(
      await request<unknown>(cfg, `/api/web/daily-plan?${q}&date=${encodeURIComponent(date)}`),
      date,
    ),
  studySummary: async (cfg: RuntimeConfig, date: string): Promise<StudySummary> =>
    normalizeStudySummary(
      await request<unknown>(cfg, `/api/web/study/summary?date=${encodeURIComponent(date)}`),
      date,
    ),
  pushPublicKey: (cfg: RuntimeConfig) =>
    request<{ ok: boolean; configured: boolean; public_key: string }>(cfg, "/api/web/push/public-key"),
  pushSubscribe: (cfg: RuntimeConfig, subscription: PushSubscriptionJSON) =>
    request<Record<string, unknown>>(cfg, "/api/web/push/subscribe", {
      method: "POST",
      body: { space_id: SPACE_ID, client_id: "between-worlds-pwa", subscription },
    }),
  pushUnsubscribe: (cfg: RuntimeConfig, endpoint: string) =>
    request<Record<string, unknown>>(cfg, "/api/web/push/unsubscribe", {
      method: "POST",
      body: { space_id: SPACE_ID, endpoint },
    }),
  pushTest: (cfg: RuntimeConfig) =>
    request<{ ok: boolean; queued?: boolean; count?: number }>(cfg, "/api/web/push/test", { method: "POST", body: { space_id: SPACE_ID } }),
  write: (cfg: RuntimeConfig, item: OutboxItem) =>
    request<Record<string, unknown>>(cfg, item.path, { method: "POST", body: { space_id: SPACE_ID, event_id: item.event_id, ...item.body } }),
};

// ---- defensive normalisers for Runtime payloads ----
export type RemoteRecord = { id: string; type: string; at: string; body: string; actor?:string; provenance?:string; entity_id?:string; entity_state?:string; related_event_id?:string; date?:string };
export type RemoteMessage = { id: string; text: string; at: string; fromCompanion: boolean; handled?: boolean; reply_to?:string };

const str = (v: unknown) => (typeof v === "string" ? v : typeof v === "number" ? String(v) : "");
const toIso = (v: unknown) => {
  if (typeof v === "number") return new Date(v < 1e12 ? v * 1000 : v).toISOString();
  const d = Date.parse(str(v));
  return Number.isNaN(d) ? new Date().toISOString() : new Date(d).toISOString();
};
const asObj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" ? (v as Record<string, unknown>) : {});

export function extractRecords(state: unknown): RemoteRecord[] {
  const root=asObj(state);
  const s = asObj(root.between ?? root.state ?? state);
  const out: RemoteRecord[] = [];
  for (const key of ["timeline", "records", "moments", "events", "notes", "life", "journal", "statuses", "status_history", "life_actions", "journal_events", "world_events"]) {
    const arr = s[key];
    if (!Array.isArray(arr)) continue;
    for (const raw of arr) {
      const r = asObj(raw);
      const meta=asObj(r.metadata_json);
      const id = str(r.event_id ?? r.id);
      if (!id) continue;
      out.push({
        id,
        type: str(r.type ?? r.kind ?? r.category) || key,
        at: toIso(r.at ?? r.created_at ?? r.timestamp ?? r.client_at),
        body: str(r.body ?? r.text ?? meta.text ?? r.subtitle ?? r.content ?? r.summary ?? r.action ?? r.value) || "（记录）",
        actor:str(meta.actor ?? meta.between_actor) || (str(r.type).startsWith('jlz_') || ['night_letter','dark_room'].includes(str(r.type)) ? 'jlz':'user'),
        provenance:str(meta.provenance_type), entity_id:str(meta.entity_id), entity_state:str(meta.entity_state), related_event_id:str(meta.related_event_id), date:str(meta.occurred_at ?? meta.date),
      });
    }
  }
  const seen = new Set<string>();
  return out.filter((r) => (seen.has(r.id) ? false : (seen.add(r.id), true)));
}

export function extractMessages(payload: unknown): RemoteMessage[] {
  const p = asObj(payload);
  const arr = Array.isArray(payload) ? payload : Array.isArray(p.messages) ? p.messages : Array.isArray(p.items) ? p.items : [];
  return arr
    .map((raw) => {
      const m = asObj(raw);
      const role = str(m.role ?? m.sender ?? m.from ?? m.author).toLowerCase();
      return {
        id: str(m.event_id || m.id),
        text: str(m.text ?? m.content ?? m.body),
        at: toIso(m.at ?? m.created_at ?? m.timestamp),
        fromCompanion: !["user", "web", "me", "yanyin", "human"].includes(role),
        handled: Boolean(m.handled), reply_to:str(m.reply_to ?? m.reply_to_id),
      };
    })
    .filter((m) => m.id && m.text);
}
