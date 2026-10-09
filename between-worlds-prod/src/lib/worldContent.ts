export const THEMES = ['mist', 'gothic', 'deepsea', 'rose'] as const;
export type Theme = typeof THEMES[number];
export type ContentEntry = { key: string; value: string; device_scope: string; effective_date: string; daypart: string; expires_at: string; revision: number; source: string; updated_at: string };
export type WorldContent = { space_id: string; revision: number; view_id: string; device_scope: string; date: string; daypart: string; entries: ContentEntry[]; values: Record<string,string>; recent_changes: {revision:number; updated_at:string; keys:string[]}[]; receipts: {device_id:string;surface:string;revision:number;applied_at:string;applied_keys?:string[]}[]; devices?:{device_id:string;device_type:string;online_state:string;last_seen_age_seconds:number}[] };
export const contentDay = (now = new Date()) => new Date(now.getTime()+8*3600000).toISOString().slice(0,10);
export const contentPart = (now = new Date()) => {
  const h = new Date(now.getTime()+8*3600000).getUTCHours();
  return h>=5&&h<11?'morning':h>=11&&h<14?'noon':h>=14&&h<18?'afternoon':h>=18&&h<22?'evening':'night';
};
export function contentValues(content: WorldContent | null, now = new Date()): Record<string,string> {
  if (!content || !Array.isArray(content.entries)) return {};
  const day=contentDay(now), part=contentPart(now);
  const rows=content.entries.filter(r=>(!r.effective_date||r.effective_date===day)&&(!r.daypart||r.daypart===part)&&(!r.expires_at||Date.parse(r.expires_at)>now.getTime())&&(r.device_scope==='shared'||r.device_scope===content.device_scope));
  rows.sort((a,b)=>Number(a.device_scope!=='shared')-Number(b.device_scope!=='shared')||Number(!!a.effective_date)-Number(!!b.effective_date)||Number(!!a.daypart)-Number(!!b.daypart)||a.revision-b.revision);
  return Object.fromEntries(rows.map(r=>[r.key,r.value]));
}
export function contentDevice(): { device_id:string; device_scope:string } {
  const native=(window as unknown as {WorldBetweenHome?: {contentIdentity?:()=>string}}).WorldBetweenHome;
  try { if(native?.contentIdentity) return JSON.parse(native.contentIdentity()); } catch { /* older APK */ }
  let id=localStorage.getItem('world-content-client');
  if(!id) { id='web-'+crypto.randomUUID(); localStorage.setItem('world-content-client',id); }
  return {device_id:id,device_scope:'shared'};
}
