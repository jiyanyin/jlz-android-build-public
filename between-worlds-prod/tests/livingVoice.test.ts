import assert from "node:assert/strict";
import test from "node:test";
import {livingVoiceSurface,parseLivingVoice} from "../src/lib/livingVoice.ts";
test("gate, another tab and an obscuring sheet cannot acknowledge invisible welcome copy",()=>{
  assert.equal(livingVoiceSurface(false,"home",false,"gate"),null);
  assert.equal(livingVoiceSurface(false,"echo",false,null),null);
  assert.equal(livingVoiceSurface(false,"home",true,null),null);
  assert.equal(livingVoiceSurface(true,"home",false,null),"welcome");
  assert.equal(livingVoiceSurface(false,"home",false,null),"home");
});
test("malformed bridge output falls back without pretending it rendered a private card",()=>{
  for(const raw of ["not json","{}",JSON.stringify({id:"x",headline:"x",body:"x"})])assert.equal(parseLivingVoice(raw),null);
  const card={id:"test",selection_id:"selection",surface:"home",headline:"A",body:"B",text:"A B",topic:"affection",bank_entries:600,bank_version:1};
  assert.equal(parseLivingVoice(JSON.stringify(card))?.selection_id,"selection");
});
