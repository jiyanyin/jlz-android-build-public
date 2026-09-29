import { AppID } from '../types';

// Only deployed within the owner-operated Android shell. A regular web/PWA
// session keeps the original SullyOS in-app behavior instead.
const nativeActions: Partial<Record<AppID, string>> = {
  [AppID.Chat]: 'chatgpt',
  [AppID.Room]: 'status_light',
  [AppID.Journal]: 'time_chain',
  [AppID.Social]: 'timeline',
  [AppID.Schedule]: 'between',
  [AppID.Study]: 'study',
  [AppID.CheckPhone]: 'diagnostics',
  [AppID.Settings]: 'permissions',
};

// No telemetry, credentials, phone contents, arbitrary Android intents,
// or dynamic arguments cross the bridge: an enumerated navigation key only.
export function tryNativeApp(id: AppID): boolean {
  const action = nativeActions[id];
  const target = (window as unknown as {
    JLZNative?: { postMessage?: (data: string) => void };
  }).JLZNative;
  if (!action || !target || typeof target.postMessage !== 'function') return false;
  target.postMessage(JSON.stringify({ action }));
  return true;
}
