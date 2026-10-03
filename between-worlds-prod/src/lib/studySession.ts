export type StudyActiveSession = {
  device_id: string;
  session_id: string;
  paused: boolean;
  started_at_ms: number;
  active_elapsed_ms: number;
  subject: string;
  module: string;
  answered_count: number;
  correct_count: number;
  wrong_count: number;
};

export type StudyFinishedSession = {
  session_id: string;
  device_id: string;
  local_date: string;
  started_at_ms: number;
  ended_at_ms: number;
  total_session_ms: number;
  active_ms: number;
  effective_study_ms: number;
  subject: string;
  module: string;
  answered_count: number;
  correct_count: number;
  wrong_count: number;
};

export type StudySummary = {
  version: string;
  date: string;
  finished_effective_ms: number;
  active_effective_ms: number;
  today_effective_ms: number;
  completed_sessions: number;
  active_session_count: number;
  active: boolean;
  paused: boolean;
  current: StudyActiveSession | null;
  active_sessions: StudyActiveSession[];
  recent_sessions: StudyFinishedSession[];
  checked_at_ms: number;
};

const obj = (value: unknown): Record<string, unknown> =>
  value && typeof value === "object" ? value as Record<string, unknown> : {};
const str = (value: unknown) =>
  typeof value === "string" ? value : typeof value === "number" ? String(value) : "";
const num = (value: unknown) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0;
};
const bool = (value: unknown) => value === true || value === 1 || value === "1" || value === "true";

const activeSession = (raw: unknown): StudyActiveSession | null => {
  const s = obj(raw);
  const id = str(s.session_id);
  if (!id) return null;
  return {
    device_id: str(s.device_id),
    session_id: id,
    paused: bool(s.paused),
    started_at_ms: num(s.started_at_ms),
    active_elapsed_ms: num(s.active_elapsed_ms),
    subject: str(s.subject),
    module: str(s.module),
    answered_count: num(s.answered_count),
    correct_count: num(s.correct_count),
    wrong_count: num(s.wrong_count),
  };
};

const finishedSession = (raw: unknown): StudyFinishedSession | null => {
  const s = obj(raw);
  const id = str(s.session_id);
  if (!id) return null;
  return {
    session_id: id,
    device_id: str(s.device_id),
    local_date: str(s.local_date),
    started_at_ms: num(s.started_at_ms),
    ended_at_ms: num(s.ended_at_ms),
    total_session_ms: num(s.total_session_ms),
    active_ms: num(s.active_ms),
    effective_study_ms: num(s.effective_study_ms),
    subject: str(s.subject),
    module: str(s.module),
    answered_count: num(s.answered_count),
    correct_count: num(s.correct_count),
    wrong_count: num(s.wrong_count),
  };
};

export const emptyStudySummary = (date: string): StudySummary => ({
  version: "jlz-study-2",
  date,
  finished_effective_ms: 0,
  active_effective_ms: 0,
  today_effective_ms: 0,
  completed_sessions: 0,
  active_session_count: 0,
  active: false,
  paused: false,
  current: null,
  active_sessions: [],
  recent_sessions: [],
  checked_at_ms: Date.now(),
});

export const normalizeStudySummary = (payload: unknown, fallbackDate: string): StudySummary => {
  const root = obj(payload);
  const raw = obj(root.study ?? payload);
  const activeSessions = (Array.isArray(raw.active_sessions) ? raw.active_sessions : [])
    .map(activeSession).filter((item): item is StudyActiveSession => !!item);
  const recentSessions = (Array.isArray(raw.recent_sessions) ? raw.recent_sessions : [])
    .map(finishedSession).filter((item): item is StudyFinishedSession => !!item);
  const current = activeSession(raw.current) ?? activeSessions[0] ?? null;
  return {
    version: str(raw.version) || "jlz-study-2",
    date: str(raw.date) || fallbackDate,
    finished_effective_ms: num(raw.finished_effective_ms),
    active_effective_ms: num(raw.active_effective_ms),
    today_effective_ms: num(raw.today_effective_ms),
    completed_sessions: num(raw.completed_sessions),
    active_session_count: num(raw.active_session_count),
    active: bool(raw.active) || activeSessions.length > 0,
    paused: current?.paused ?? bool(raw.paused),
    current,
    active_sessions: activeSessions,
    recent_sessions: recentSessions,
    checked_at_ms: num(raw.checked_at_ms) || Date.now(),
  };
};

export const liveStudyTotals = (summary: StudySummary, nowMs = Date.now()) => {
  const delta = Math.max(0, nowMs - summary.checked_at_ms);
  const running = summary.active_sessions.filter((item) => !item.paused).length;
  const todayMs = summary.today_effective_ms + delta * running;
  const current = summary.current
    ? summary.current.active_elapsed_ms + (summary.current.paused ? 0 : delta)
    : 0;
  return { todayMs, currentMs: current };
};

export const formatStudyClock = (milliseconds: number) => {
  const seconds = Math.floor(Math.max(0, milliseconds) / 1000);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  return [hours, minutes, rest].map((value) => String(value).padStart(2, "0")).join(":");
};

export const formatStudyMinutes = (milliseconds: number) => {
  const minutes = Math.floor(Math.max(0, milliseconds) / 60_000);
  if (minutes < 60) return `${minutes} 分钟`;
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
};

export const studyDeviceLabel = (deviceId: string) =>
  deviceId.toLowerCase().includes("tablet") ? "平板" :
    deviceId.toLowerCase().includes("phone") ? "手机" : "设备";
