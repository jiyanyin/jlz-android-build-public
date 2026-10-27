export type LivingVoiceCard = {
  id:string; selection_id:string; headline:string; body:string; text:string;
  topic:string; bank_version:number; bank_entries:number; surface:"home"|"welcome";
};
export function livingVoiceSurface(welcome:boolean,tab:string,hasSheet:boolean,entryMode:string|null):"welcome"|"home"|null {
  if(entryMode==="gate")return null;
  return welcome?"welcome":tab==="home"&&!hasSheet?"home":null;
}
export function parseLivingVoice(raw:string):LivingVoiceCard|null {
  try {
    const c=JSON.parse(raw) as LivingVoiceCard;
    if(!c || !c.id || !c.selection_id || !["home","welcome"].includes(c.surface) ||
      typeof c.headline!=="string" || typeof c.body!=="string" || typeof c.text!=="string" ||
      c.text.length>600 || c.bank_entries<2 || c.bank_version<1) return null;
    return c;
  } catch {return null;}
}
