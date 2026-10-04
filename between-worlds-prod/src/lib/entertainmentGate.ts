export type GateChoice = "purpose" | "break" | "small_step" | "direct";

export type EntertainmentGateSnapshot = {
  ok: boolean;
  package_name: string;
  app_name: string;
  tier: string;
  device_type: "phone" | "tablet" | string;
  incoming_text: string;
  purpose_minutes: number;
  break_minutes: number;
  direct_minutes: number;
  small_step_minutes: number;
  small_step_pending: boolean;
  small_step_done: boolean;
  small_step_remaining_ms: number;
  small_step_text: string;
  current_effective_ms: number;
  error?: string;
};

type NativeGateBridge = {
  snapshot?: (packageName: string, reason: string) => string;
  response?: (packageName: string, choice: string) => string;
  grant?: (packageName: string, choice: string) => string;
  startSmallStep?: (packageName: string) => string;
  cancelSmallStep?: (packageName: string) => boolean;
  decline?: (packageName: string, stage: string) => boolean;
  enabled?: () => boolean;
};

declare global {
  interface Window {
    WorldBetweenGate?: NativeGateBridge;
  }
}

const empty = (packageName: string): EntertainmentGateSnapshot => ({
  ok: false,
  package_name: packageName,
  app_name: "这个 App",
  tier: "feed",
  device_type: "phone",
  incoming_text: "先看我一眼。进去之前，告诉我你准备做什么。",
  purpose_minutes: 5,
  break_minutes: 5,
  direct_minutes: 3,
  small_step_minutes: 5,
  small_step_pending: false,
  small_step_done: false,
  small_step_remaining_ms: 0,
  small_step_text: "",
  current_effective_ms: 0,
});

const numberValue = (value: unknown, fallback = 0) => {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
};

export const normalizeGateSnapshot = (
  payload: unknown,
  packageName: string
): EntertainmentGateSnapshot => {
  const raw = payload && typeof payload === "object"
    ? payload as Record<string, unknown>
    : {};
  return {
    ...empty(packageName),
    ok: raw.ok === true,
    package_name: String(raw.package_name || packageName),
    app_name: String(raw.app_name || "这个 App"),
    tier: String(raw.tier || "feed"),
    device_type: String(raw.device_type || "phone"),
    incoming_text: String(raw.incoming_text || ""),
    purpose_minutes: numberValue(raw.purpose_minutes, 5),
    break_minutes: numberValue(raw.break_minutes, 5),
    direct_minutes: numberValue(raw.direct_minutes, 3),
    small_step_minutes: numberValue(raw.small_step_minutes, 5),
    small_step_pending: raw.small_step_pending === true,
    small_step_done: raw.small_step_done === true,
    small_step_remaining_ms: numberValue(raw.small_step_remaining_ms, 0),
    small_step_text: String(raw.small_step_text || ""),
    current_effective_ms: numberValue(raw.current_effective_ms, 0),
    error: raw.error ? String(raw.error) : undefined,
  };
};

export const readGateSnapshot = (
  packageName: string,
  reason = "entry"
): EntertainmentGateSnapshot => {
  const bridge = window.WorldBetweenGate;
  if (!bridge?.snapshot) return empty(packageName);
  try {
    return normalizeGateSnapshot(
      JSON.parse(bridge.snapshot(packageName, reason)),
      packageName
    );
  } catch {
    return empty(packageName);
  }
};

export const gateResponse = (packageName: string, choice: GateChoice): string =>
  window.WorldBetweenGate?.response?.(packageName, choice) ||
  "好。我听见了。";

export const grantGate = (
  packageName: string,
  choice: GateChoice
): { ok: boolean; minutes: number; until_ms?: number } => {
  try {
    const raw = JSON.parse(
      window.WorldBetweenGate?.grant?.(packageName, choice) || "{}"
    ) as Record<string, unknown>;
    return {
      ok: raw.ok === true,
      minutes: numberValue(raw.minutes, 0),
      until_ms: numberValue(raw.until_ms, 0) || undefined,
    };
  } catch {
    return { ok: false, minutes: 0 };
  }
};

export const startGateSmallStep = (packageName: string): boolean => {
  try {
    const raw = JSON.parse(
      window.WorldBetweenGate?.startSmallStep?.(packageName) || "{}"
    ) as Record<string, unknown>;
    return raw.ok === true;
  } catch {
    return false;
  }
};

export const cancelGateSmallStep = (packageName: string): boolean =>
  window.WorldBetweenGate?.cancelSmallStep?.(packageName) ?? false;

export const declineGate = (packageName: string, stage: string): boolean =>
  window.WorldBetweenGate?.decline?.(packageName, stage) ?? false;

export const gateBridgeAvailable = () =>
  !!window.WorldBetweenGate?.snapshot && !!window.WorldBetweenGate?.grant;
