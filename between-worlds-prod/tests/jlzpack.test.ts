import assert from 'node:assert/strict';
import test from 'node:test';
import {validateInstructionPack,exportWorldPack} from '../src/lib/jlzpack.ts';

test('export allowlist excludes authentication and queued transport',()=>{
 const pack=JSON.stringify(exportWorldPack({webToken:'SECRET',runtimeUrl:'SECRET',outbox:[{token:'SECRET'}],dailyPlan:{tasks:[]}}));
 assert.ok(!pack.includes('SECRET'));
 assert.equal(JSON.parse(pack).image_bytes_included,false);
});
test('all supported operations validate before any application',()=>{
 const p={schema_version:'jlz-instructions-1',pack_id:'pack-1',actions:[
  {type:'daily_task',action:'upsert',task_id:'a',title:'Task'},
  {type:'message',text:'Hello'}, {type:'gate_config',enabled:false},
  {type:'world_settings',theme:'mist'}, {type:'jlz_letter',text:'Letter'},
  {type:'reminder',title:'Reminder',due_at:'2026-10-08T10:00:00+08:00'}]};
 assert.equal(validateInstructionPack(JSON.stringify(p)).actions.length,6);
});
test('rejects executable, oversized, malformed and secret-bearing instructions',()=>{
 for(const actions of [[{type:'shell',text:'rm'}],[{type:'gate_config',enabled:'false'}],[{type:'world_settings',theme:'other'}],
  [{type:'daily_task',action:'delete'}],[{type:'note',text:'hi',token:'secret'}],[{type:'note',text:'x'.repeat(4001)}]]) {
  assert.throws(()=>validateInstructionPack(JSON.stringify({schema_version:'jlz-instructions-1',pack_id:'a',actions})));
 }
 assert.throws(()=>validateInstructionPack(' '.repeat(524289)));
});
