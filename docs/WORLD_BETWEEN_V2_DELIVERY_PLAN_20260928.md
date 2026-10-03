# 世界之间 V2｜增量交付总纲
Date: 2026-09-28; status: DESIGN FROZEN FOR FIRST PASS / NO PRODUCTION MERGE

## Product
World Between is the user's **real Android home/launcher and personal life cockpit**, not a virtual "AI phone".
Design is a light icy-blue/lilac glass aesthetic; the relationship ("你我之间") is the heart, while daily tasks, app routing and life observations are real-world operations.
The user chose **B: context-aware unlock welcome**. It must not interrupt emergencies, phone calls, system access, or short repeat unlocks.

## Sources and non-negotiable engineering constraints
- Existing official Android source snapshot: jiyanyin/jlz-android-build-public `main` (public build mirror). Real-device installation can be ahead of this mirror; reconcile with latest source before merging.
- Existing private runtime: jiyanyin/jlz-runtime-private; Android and server are different systems.
- Existing original IB Mobile: Sui-IB/InternalBeyond-Mobile is **non-commercial licensed**. This v2 UI lab contains *original* code, not copied IB code or art; review licenses, attribution and permission separately if importing upstream visual assets.
- No production manifest, app ID, data table, permissions, signing, captures, users, screenshot history or phone-control behavior changed in this UI lab.
- Old build-and-release workflow triggers on `main` only. Lab must never update the production latest APK.
- A source-level stub is not a true phone PASS. Distinguish preview, build pass, instrumented test, and phone-verified.

## Phases and exit conditions

### P0 — frozen feature map + isolated scaffolding
Inventory launcher, status light, quick note, life journal, timeline, app gate, study, screenshot, notifications, phone state, companion transport, location and existing Runtime APIs. Record provenance and missing adapters.
Deliver this plan and a separate opt-in UI experiment package. No production change.

### P1 — visual shell
Provide usable original-design **preview** screens: context welcome, home, task card, "你我之间", status-light summary, half-screen action recorder, schedule, app drawer and timeline navigation. Keep design tokens for translucency, corners, shadow and type. Show preview/demo-data badge, never present simulated counts as device facts.
**Exit:** buildable APK plus on-device appearance/scroll/tap QA; landscape/tablet adaptivity investigated.

### P2 — connect existing native features
Route home links to installed **Banduread** shortcut/URL and Fenbi native app with explicit fallback; expose existing launcher app drawer; connect user note capture, status light, Focus/Study, calendar, usage, position and native inbox via real interfaces. Preserve currently working client behavior.
**Exit:** one fully proven loop at a time, with observed timestamp, launch result, failure mode and phone acceptance.

### P3 — event system and bidirectional timelines
- `life_events`: explicit user taps with start/end/instant; inferred observation is *separate*, never misrepresented as user action.
- `pending_inbox`: spontaneous text, photo, status-light comments, needs_reply default true, exact original text retained; processing ack only after actual reading and reply.
- `assistant_actions`: observations and their source, planned/sent/completed/failed tasks, proactive contacts, changed plans and explanations.
- `joint_moments`: curated mutually meaningful stories with references to raw sources. Hidden surprise *drafts* may be private, but phone-control / locks / data access must remain auditable.
Preserve raw records and mark edits/backfills; do not auto-upgrade a temporary mood to Memory.

### P4 — authenticated hourly official-GPT loop
Aggregate last-hour device / manual / study / pending inbox / last plan with dedup, pagination and provenance. Only assert official GPT saw information after an actual authorized scheduled run and receipt. Plan scheduling, real phone execution receipt, requested user reply and dedup safe retry. Question game: highlight conflicting observations as hypotheses, not proof of deception.

### P5 — original high-fidelity visual + full acceptance
Finalize dynamic themes, provided character assets with permitted license, animation and first-run/onboarding; battery/RAM/low-end performance; airplane-mode fallback, rotation/tablet and OS limitations; regression for focus, accessibility, screenshots, notifications, privacy and escape routes.
Ship only after user-confirmed real device PASS. Production source of truth must be reconciled with current installed build.

## Home IA
1. Contextual top / JLZ welcome / explicit last-verified status.
2. **One** main task card with status: proposed → active → verified or expired. App opened alone != task completed.
3. "你我之间" entry containing summary of current status and a *real* last message; raw machine logs stay out of narrative timeline.
4. Compact quick actions: user events (bottom half-sheet with categories), quick note, study, schedule.
5. Real launcher dock + full app drawer. Essential communications, OS settings and emergencies must be available.
6. Device diagnostic / data provenance accessible but not crowding main screen.

## Event capture behavior
- One tap opens a ~half-height modal selector. Selecting a one-shot event stores current timestamp, closes sheet and shows undo.
- Timed event: creates a start; end button sets end timestamp; overdue/missing end remains incomplete; backfill marks corrected provenance.
- Categories: routine, meals/water, bathroom, hygiene, sleep, exercise/walk, study/work, free time and social/pet. Favorites reorderable.
- A 'walk' tap is not permission to start GPS; show explicit "同行" option for location service.
- A 'sleep' tap is not a verified asleep timestamp.
- Quick note must be handled in an inbox separate from life event; actor "user" only when user explicitly submitted it.
- Two distinct timelines: full source-tagged event ledger vs curated relationship timeline.

## Acceptance matrix / safety
- UI preview can render offline; offline message when no real data.
- Phone current version may differ from public mirror. Never overwrite without latest code validation.
- No unapproved paid or irreversible actions, no hidden use of camera/mic/location, no guessing subjective internal states.
- No unescapable phone locks; emergency and critical native functions accessible.
- No implicit capture or publishing of user's personal information in public mirror.
