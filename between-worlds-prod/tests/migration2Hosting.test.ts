import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const runtimeSource = readFileSync(new URL("../src/lib/runtime.ts", import.meta.url), "utf8");
const appSource = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");

test("Runtime host is build-configurable with legacy Render fallback", () => {
  assert.match(runtimeSource, /import\.meta\.env\.VITE_RUNTIME_URL/);
  assert.match(runtimeSource, /LEGACY_RUNTIME_URL\s*=\s*"https:\/\/jlz-palm-server\.onrender\.com"/);
  assert.match(runtimeSource, /configuredRuntimeUrl\s*\|\|\s*LEGACY_RUNTIME_URL/);
});

test("browser install intent follows the current WebShell origin", () => {
  assert.match(appSource, /window\.location\.host/);
  assert.match(appSource, /window\.location\.href/);
  assert.doesNotMatch(
    appSource,
    /const chromeIntent\s*=\s*"intent:\/\/between-worlds-prod\.onrender\.com/
  );
});
