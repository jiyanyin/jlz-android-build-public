export type NativeLifeEnd = {
  event_id: string; session_id: string; action: string;
  start_at_ms: number; end_at_ms: number;
};

type LifeBridge = {
  begin?: (payload: string) => string;
  finish?: (sessionId: string) => string;
  pendingEnds?: () => string;
  acknowledged?: (eventId: string) => string;
  reminderStatus?: () => string;
};
declare global { interface Window { WorldBetweenLife?: LifeBridge } }

const object = (raw: unknown): Record<string,unknown> => {
  if(!raw || typeof raw!=="object" || Array.isArray(raw))return {};
  return raw as Record<string,unknown>;
};
export const nativeLifeAvailable = () => typeof window.WorldBetweenLife?.begin==="function";
export const beginNativeLife=(action:string,session_id:string,start_at_ms:number)=>{
  if(!nativeLifeAvailable())return {ok:false,native:false};
  try {
    const res=object(JSON.parse(window.WorldBetweenLife!.begin!(JSON.stringify({action,session_id,start_at_ms}))));
    return {ok:res.ok===true,native:true,notificationAvailable:res.notice_permission_ready===true,
      code:String(res.code||"")};
  }catch{return {ok:false,native:true,code:"bridge_failed"}}
};
export const finishNativeLife=(sessionId:string)=>{
  try {window.WorldBetweenLife?.finish?.(sessionId)}catch{ /* Web record still queues its end */ }
};
export const pendingNativeLifeEnds=():NativeLifeEnd[]=>{
  try {
    const all=JSON.parse(window.WorldBetweenLife?.pendingEnds?.()||"[]") as unknown;
    if(!Array.isArray(all))return [];
    return all.map(object).filter(x=>typeof x.event_id==="string" && typeof x.session_id==="string" &&
       typeof x.action==="string" && Number.isFinite(Number(x.start_at_ms)) &&
       Number.isFinite(Number(x.end_at_ms)))
      .map(x=>({event_id:String(x.event_id),session_id:String(x.session_id),action:String(x.action),
        start_at_ms:Number(x.start_at_ms),end_at_ms:Number(x.end_at_ms)}));
  }catch{return []}
};
export const acknowledgedNativeLifeEnd=(eventId:string):boolean=>{
  try {return object(JSON.parse(window.WorldBetweenLife?.acknowledged?.(eventId)||"{}")).ok===true}
  catch{return false}
};
