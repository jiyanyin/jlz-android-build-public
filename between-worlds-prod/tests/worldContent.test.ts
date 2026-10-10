import {test} from 'node:test';
import assert from 'node:assert/strict';
import {contentValues,contentPart,contentDay,type WorldContent} from '../src/lib/worldContent.ts';
const row=(value:string,extra={})=>({key:'welcome.line',value,device_scope:'shared',effective_date:'',daypart:'',expires_at:'',revision:1,source:'official_gpt',updated_at:'',...extra});
test('offline daily content expires at Shanghai midnight and restores evergreen value',()=>{
  const content={device_scope:'phone',entries:[row('fallback'),row('today',{effective_date:'2026-10-09',expires_at:'2026-10-09T16:00:00Z',revision:2})]} as WorldContent;
  assert.equal(contentValues(content,new Date('2026-10-09T15:59:59Z'))['welcome.line'],'today');
  assert.equal(contentValues(content,new Date('2026-10-09T16:00:00Z'))['welcome.line'],'fallback');
  assert.equal(contentDay(new Date('2026-10-09T16:00:00Z')),'2026-10-10');
});
test('device override and daypart are resolved from cached rows, never stale values',()=>{
  const content={device_scope:'tablet',entries:[row('shared'),row('tablet',{device_scope:'tablet'}),row('evening',{daypart:'evening',device_scope:'tablet'})],values:{'welcome.line':'stale'}} as WorldContent;
  assert.equal(contentValues(content,new Date('2026-10-09T12:00:00Z'))['welcome.line'],'evening');
  assert.equal(contentValues(content,new Date('2026-10-09T15:00:00Z'))['welcome.line'],'tablet');
  assert.equal(contentPart(new Date('2026-10-09T12:00:00Z')),'evening');
});
