import {test} from 'node:test';
import assert from 'node:assert/strict';
import {roomEntries} from '../src/lib/worldEntries.ts';
test('letter state updates preserve body, and wish read view selects latest entity',()=>{
 const records=[{id:'a',type:'night_letter',at:'2026-10-09T10:00Z',body:'letter',entity_id:'letter-1',entity_state:'draft'},
 {id:'b',type:'jlz_letter_state',at:'2026-10-09T11:00Z',body:'sent note',entity_id:'letter-1',entity_state:'sent'},
 {id:'c',type:'jlz_wish',at:'2026-10-09T10:00Z',body:'old wish',entity_id:'wish-1'},
 {id:'d',type:'jlz_wish',at:'2026-10-09T11:00Z',body:'new wish',entity_id:'wish-1'}];
 const view=roomEntries(records);
 assert.equal(view.length,2);assert.equal(view.find(r=>r.entity_id==='letter-1')?.body,'letter');
 assert.equal(view.find(r=>r.entity_id==='letter-1')?.entity_state,'sent');
 assert.equal(view.find(r=>r.entity_id==='wish-1')?.body,'new wish');
});
import {cycleWindow} from '../src/lib/worldEntries.ts';
test('cycle estimate uses observed gaps only and excludes corrected starts',()=>{
 const starts=[{id:'a',date:'2026-07-01',kind:'period_start'},{id:'b',date:'2026-07-29',kind:'period',event:'start'},{id:'c',date:'2026-08-28',kind:'period_start'}];
 assert.deepEqual(cycleWindow(starts),{from:'2026-09-25',to:'2026-09-27',intervals:2});
 assert.equal(cycleWindow(starts.slice(0,2)),null);
 assert.equal(cycleWindow([...starts,{id:'fix',kind:'correction',related_event_id:'b'}]),null);
 assert.equal(cycleWindow([...starts,{id:'fix',kind:'correction'}]),null);
 assert.deepEqual(cycleWindow([...starts,starts[1]]),cycleWindow(starts));
});
