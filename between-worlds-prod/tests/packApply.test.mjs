import assert from 'node:assert/strict';
import test from 'node:test';
import {stageInstructionPack} from '../src/lib/packApply.ts';
const initial=()=>({theme:'mist',dailyPlan:null,outbox:[],notes:[],importedMessages:[],packAudit:[],pendingPackGate:null});
const pack={schema_version:'jlz-instructions-1',pack_id:'one',actions:[{type:'daily_task',task_id:'a',action:'upsert',title:'A'},{type:'message',text:'Hello'},{type:'gate_config',enabled:false}]};
test('one staged snapshot contains content, durable outbox, Gate intent and replay audit',()=>{
 const before=initial(), next=stageInstructionPack(before,pack,'2026-10-08','2026-10-08T00:00:00Z');
 assert.deepEqual(before,initial());
 assert.equal(next.dailyPlan.tasks[0].title,'A');
 assert.equal(next.importedMessages[0].text,'Hello');
 assert.equal(next.pendingPackGate,false);
 assert.equal(next.outbox[0].event_id,'pack-one-0');
 const reopened=JSON.parse(JSON.stringify(next));
 assert.throws(()=>stageInstructionPack(reopened,pack,'2026-10-08','later'),/已经/);
 assert.equal({...reopened,remoteMessages:[]}.importedMessages.length,1);
});
test('queue capacity rejects entire pack without dropping unsent user actions',()=>{
 const before={...initial(),outbox:Array.from({length:300},(_,i)=>({event_id:String(i)}))};
 assert.throws(()=>stageInstructionPack(before,pack,'2026-10-08','now'),/队列/);
 assert.equal(before.packAudit.length,0);assert.equal(before.outbox.length,300);assert.equal(before.dailyPlan,null);
});
