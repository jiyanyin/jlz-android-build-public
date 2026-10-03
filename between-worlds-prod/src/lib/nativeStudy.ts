export type NativeStudySnapshot = {
  version: string;
  captured_at_ms: number;
  active: boolean;
  paused: boolean;
  session_id: string;
  started_at_ms: number;
  current_ms: number;
  today_ms: number;
  finished_today_ms: number;
  completed_sessions: number;
  stale_recovered: boolean;
  stale_reason: string;
};

type NativeStudyBridge = {
  version?: () => string;
  snapshot?: () => string;
  start?: () => string;
  pause?: () => string;
  resume?: () => string;
  finish?: () => string;
  openBanduread?: () => string;
  openFenbi?: () => string;
};

declare global {
  interface Window {
    WorldBetweenStudy?: NativeStudyBridge;
  }
}

const empty = (): NativeStudySnapshot => ({
  version: "study-bridge-none",
  captured_at_ms: Date.now(),
  active: false,
  paused: false,
  session_id: "",
  started_at_ms: 0,
  current_ms: 0,
  today_ms: 0,
  finished_today_ms: 0,
  completed_sessions: 0,
  stale_recovered: false,
  stale_reason: "",
});

const num = (value: unknown) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0;
};

export const normalizeNativeStudySnapshot = (payload: unknown): NativeStudySnapshot => {
  const raw = payload && typeof payload === "object"
    ? payload as Record<string, unknown>
    : {};
  return {
    ...empty(),
    version: typeof raw.version === "string" ? raw.version : "study-bridge-none",
    captured_at_ms: num(raw.captured_at_ms) || Date.now(),
    active: raw.active === true,
    paused: raw.paused === true,
    session_id: typeof raw.session_id === "string" ? raw.session_id : "",
    started_at_ms: num(raw.started_at_ms),
    current_ms: num(raw.current_ms),
    today_ms: num(raw.today_ms),
    finished_today_ms: num(raw.finished_today_ms),
    completed_sessions: num(raw.completed_sessions),
    stale_recovered: raw.stale_recovered === true,
    stale_reason: typeof raw.stale_reason === "string" ? raw.stale_reason : "",
  };
};

const call = (method: keyof NativeStudyBridge): NativeStudySnapshot => {
  const fn = window.WorldBetweenStudy?.[method];
  if (typeof fn !== "function") return empty();
  try {
    return normalizeNativeStudySnapshot(JSON.parse((fn as () => string)()));
  } catch {
    return empty();
  }
};

export const nativeStudyAvailable = () =>
  typeof window.WorldBetweenStudy?.snapshot === "function";

export const readNativeStudy = () => call("snapshot");
export const startNativeStudy = () => call("start");
export const pauseNativeStudy = () => call("pause");
export const resumeNativeStudy = () => call("resume");
export const finishNativeStudy = () => call("finish");

export const openNativeBanduread = () =>
  window.WorldBetweenStudy?.openBanduread?.() || "";

export const openNativeFenbi = () =>
  window.WorldBetweenStudy?.openFenbi?.() || "";

export const liveNativeStudy = (
  snapshot: NativeStudySnapshot,
  nowMs = Date.now()
) => {
  if (!snapshot.active || snapshot.paused) {
    return { currentMs: snapshot.current_ms, todayMs: snapshot.today_ms };
  }
  const delta = Math.max(0, nowMs - snapshot.captured_at_ms);
  return {
    currentMs: snapshot.current_ms + delta,
    todayMs: snapshot.today_ms + delta,
  };
};
