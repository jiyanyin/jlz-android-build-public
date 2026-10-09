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
