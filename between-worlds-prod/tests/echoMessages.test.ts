import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { extractMessages, mergeEchoMessages, pendingEchoMessages } from "../src/lib/echoMessages.ts";
import { BANDUREAD_URL } from "../src/lib/studyLinks.ts";

const body="A complete paragraph with line breaks.\n\n".repeat(25);
const rows=[
  {id:"reply-id",event_id:"input-id",role:"companion",text:body,created_at:"2026-10-28T02:09:52Z",handled:true},
  {id:"input-id",event_id:"input-id",role:"user",text:"Working",created_at:"2026-10-28T01:57:34Z"},
];

test("a long reply and the user input sharing an event remain two complete bubbles",()=>{
  const parsed=extractMessages({messages:rows});
  assert.deepEqual(parsed.map(m=>m.id),["reply-id","input-id"]);
  assert.equal(parsed[0].text,body);
  assert.equal(mergeEchoMessages(parsed).length,2);
  assert.equal(parsed[0].fromCompanion,true);
  assert.equal(parsed[1].fromCompanion,false);
});
test("several replies to one input survive history paging and repeated polling",()=>{
  const newer=extractMessages([{...rows[0],id:"reply-2",text:"Another reply",created_at:"2026-10-28T02:12:00Z"}]);
  const history=extractMessages(rows);
  const display=mergeEchoMessages(history,newer,history,newer);
  assert.deepEqual(display.map(m=>m.id),["reply-2","reply-id","input-id"]);
  assert.equal(display.find(m=>m.id==="reply-id")?.text,body);
});
test("a recent poll omitting an already loaded reply does not remove it",()=>{
  const loaded=extractMessages(rows);
  const partial=extractMessages([rows[1]]);
  assert.equal(mergeEchoMessages(loaded,partial,[]).find(m=>m.id==="reply-id")?.text,body);
});
test("only the accepted user message reconciles the local outbox bubble",()=>{
  const local=[{event_id:"input-id",text:"Working"},{event_id:"unsent",text:"Pending"}];
  const parsed=extractMessages(rows);
  assert.equal(pendingEchoMessages(local,parsed.slice(0,1)).length,2);
  assert.deepEqual(pendingEchoMessages(local,parsed).map(m=>m.event_id),["unsent"]);
  const native=extractMessages([{...rows[1],id:"native-user-row",client_event_id:"input-id"}]);
  assert.deepEqual(pendingEchoMessages(local,native).map(m=>m.event_id),["unsent"]);
  const relatedInput=extractMessages([{...rows[1],id:"native-user-row",source:"jlz_native_android"}]);
  assert.equal(pendingEchoMessages(local,relatedInput).length,2);
});
test("same text on distinct ids remains distinct; event-only legacy messages still work",()=>{
  const a=extractMessages([{...rows[1],id:"one"},{...rows[1],id:"two"}]);
  assert.equal(mergeEchoMessages(a).length,2);
  assert.equal(extractMessages([{event_id:"legacy",text:"Kept",role:"user",created_at_ms:1793152654215}])[0].id,"legacy");
});
test("web and native launches share the formal migrated study URL",()=>{
  assert.equal(BANDUREAD_URL,"https://laptop-p23k8ciu.tail9f1176.ts.net:8443/");
  const native=readFileSync(new URL("../../native-android/app/src/main/java/dev/jlz/presence/study/StudyShortcuts.kt",import.meta.url),"utf8");
  assert.ok(native.includes(`const val BANDUREAD_URL = "${BANDUREAD_URL}"`));
});
