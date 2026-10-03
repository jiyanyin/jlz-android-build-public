# Stage B · Voice Engine + Dynamic Time Card

Status: implementation branch `feature/world-between-control-v1-20261003`.

## Goal

Replace the fixed Home greeting with a lightweight local JLZ voice engine that feels responsive to the user's actual moment without spending model/API quota.

The Home hero becomes:

1. exact local clock to the second;
2. current date;
3. a small context label;
4. one selected JLZ headline;
5. one selected JLZ body line.

## Inputs

Stage B v1 intentionally uses only data already present in the local app state:

- device-local date/time;
- current `activeLife` action and elapsed minutes;
- the latest Status Light axes;
- Status Light detail choices, especially:
  - `想听我怎样回应`
  - `我想怎样和你亲近`
  - emotion/body/appearance selections.

It does **not** infer hidden emotions from arbitrary note text and does not make medical conclusions.

## Selection priority

Highest to lowest:

1. explicit response preference (quiet / brief / strong / teasing / affectionate);
2. fresh distress-like Status Light signal;
3. current active-life activity;
4. fresh positive Status Light signal;
5. daypart fallback.

Status Light context expires after six hours so an old difficult moment does not keep coloring the Home card all day.

An active-life timer is ignored after twelve hours as a stale safety boundary.

## Stability and anti-repeat

- Voice context is frozen into 30-minute slots.
- The line stays stable inside the same slot and same context.
- A changed Status Light or active activity changes the context immediately.
- Recently used lines have a six-hour cooldown.
- If a signal bank is temporarily exhausted, the least-recently-used lines become eligible first.
- Selection is deterministic from the context key; page re-renders do not randomly flicker copy.

The local memory keeps only the latest 64 voice selections.

## Voice Pack contract

`VoicePack` is a versioned data contract:

- `id`
- `version`
- `entries[]`

Each entry contains:

- stable `id`
- semantic `signal`
- `headline`
- `body`

The selector accepts an optional pack argument. This keeps Stage B ready for a future official/remote Voice Pack without changing Home UI or selection logic.

Built-in pack: `jlz-core@1`.

## Current content

The built-in pack includes context banks for:

- quiet / concise / strong / affectionate / teasing response styles;
- distress / bright state;
- study / work / meals / rest / movement / routines / leisure / body discomfort / sleep / wake / chat;
- deep night / dawn / morning / noon / afternoon / evening / late night;
- general fallback.

## Rendering

Primary surface: `between-worlds-prod`.

The exact time updates once per second. Voice selection does **not** rotate every second because its context key is slot-based.

Stage B does not create a second independent native voice engine. The Android app's primary UI is the production WebShell, so duplicating the same sentence-selection logic natively would create drift.

## Tests

`tests/voiceEngine.test.ts` verifies:

- seconds are rendered;
- same-context stability;
- next-slot anti-repeat behavior;
- active study outranks ordinary daypart;
- explicit quiet preference outranks activity;
- distress context outranks activity;
- stale Status Light context is ignored.

CI runs those tests with Node's type stripping, then TypeScript + Vite production build.

## Non-goals

- no LLM call;
- no external model dependency;
- no Stage G incoming-call gate redesign;
- no new notification scheduling;
- no official latest APK publish from the PR.

## Stage B acceptance

1. Home no longer contains the fixed `下午好，音音`-style greeting generator.
2. Home clock visibly includes seconds.
3. Voice changes on meaningful context changes while staying stable within one context slot.
4. Recent voice lines do not immediately repeat.
5. Old Status Light state expires.
6. Voice tests pass.
7. TypeScript/Vite production build passes.
8. Android PR build remains green and latest release publication remains skipped.
