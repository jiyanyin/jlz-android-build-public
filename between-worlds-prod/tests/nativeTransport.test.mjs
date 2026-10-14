import assert from 'node:assert/strict';
import test from 'node:test';
import {nativeRequest} from '../src/lib/nativeTransport.ts';

test('native network requests complete independently and ignore unrelated responses',async()=>{
 const target=new EventTarget(), pending=[];
 const bridge={request(){throw Error('synchronous transport must not run');},requestAsync(id){pending.push(id);}};
 const first=nativeRequest(bridge,'/first','GET','',target);
 const second=nativeRequest(bridge,'/second','GET','',target);
 assert.equal(pending.length,2);
 target.dispatchEvent(new CustomEvent('home-node-response',{detail:{id:'other',response:'wrong'}}));
 target.dispatchEvent(new CustomEvent('home-node-response',{detail:{id:pending[1],response:'second'}}));
 assert.equal(await second,'second');
 target.dispatchEvent(new CustomEvent('home-node-response',{detail:{id:pending[0],response:'first'}}));
 assert.equal(await first,'first');
});
test('offline timeout rejects without blocking the event loop',async()=>{
 const p=nativeRequest({request(){return '';},requestAsync(){}},'/offline','GET','',new EventTarget(),10);
 let responsive=false;queueMicrotask(()=>{responsive=true;});
 await assert.rejects(p,/timeout/);assert.equal(responsive,true);
});
