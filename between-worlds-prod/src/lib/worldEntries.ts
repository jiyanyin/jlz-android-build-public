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
