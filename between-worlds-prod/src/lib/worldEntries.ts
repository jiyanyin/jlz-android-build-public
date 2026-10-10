type Entry={id:string;type:string;at:string;body:string;entity_id?:string;entity_state?:string;related_event_id?:string};
/** Read view only. State changes never erase letter text or historical records. */
export function roomEntries<T extends Entry>(records:T[]):T[]{
  const ordered=[...records].sort((a,b)=>Date.parse(a.at)-Date.parse(b.at));
  const current=new Map<string,T>(), states=new Map<string,string>();
  for(const r of ordered){
    const identity=r.entity_id||r.id;
    if(r.entity_state)states.set(identity,r.entity_state);
    if(r.type==='jlz_letter_state')continue;
    if(r.type.startsWith('jlz_')||['night_letter','dark_room'].includes(r.type))current.set(identity,r);
  }
  return [...current.entries()].map(([id,r])=>({...r,entity_state:states.get(id)||r.entity_state})).filter(r=>r.entity_state!=='abandoned').sort((a,b)=>Date.parse(b.at)-Date.parse(a.at));
}

/** Historical interval range only: no model, default cycle length, or medical inference. */
export function cycleWindow(records: {id:string; date?:string; kind?:string; event?:string; related_event_id?:string}[]) {
  const corrected = new Set(records.filter(r=>r.kind==='correction').map(r=>r.related_event_id).filter(Boolean));
  // An untargeted correction cannot be safely applied to a particular start.
  if(records.some(r=>r.kind==='correction'&&!r.related_event_id)) return null;
  const dates=[...new Set(records.filter(r=>!corrected.has(r.id)&&(r.kind==='period_start'||(r.kind==='period'&&r.event==='start'))).map(r=>r.date?.slice(0,10)).filter((d):d is string=>!!d&&/^\d{4}-\d{2}-\d{2}$/.test(d)))].sort();
  if(dates.length<3) return null;
  const starts=dates.slice(-6).map(d=>Date.parse(d+'T00:00:00Z'));
  if(starts.some(d=>!Number.isFinite(d))) return null;
  const gaps=starts.slice(1).map((d,i)=>(d-starts[i])/86400000);
  const after=(days:number)=>new Date(starts[starts.length-1]+days*86400000).toISOString().slice(0,10);
  return {from:after(Math.min(...gaps)),to:after(Math.max(...gaps)),intervals:gaps.length};
}
