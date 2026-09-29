# Mirror provenance

This repository is a build-only public mirror of the Android client.

P0-2 test source snapshot: private Android branch `feat/p0-2-android-between-20260928`, commit `6342857d44485d3b118c59d484cd58cadb906a1b` (application source only).
This feature is isolated on public branch `ci/p0-2-between-android-20260928`, not public `main`.
Private source signing configuration is replaced with the public mirror's existing signing-safe Gradle file, with test versionCode `2026092822`.
A separately configured GitHub Actions secret provides the existing stable signing certificate at build time.
The workflow only publishes a test release tag; it never overwrites `android-native-latest`.

Excluded by design: private Git history, Runtime/MCP/server sources, keystores, credentials, captures, user data, environment files, and private deployment configuration.

P0-2 expanded state-light UI snapshot from private commit `274bc1c81b8af2fe294dc037ca423bd805320a72` plus `StatusLightEditor.kt`; this test build has versionCode `2026092823` and is not a production release. Public build changes include only Android Kotlin source and test-version metadata.

P0-2 home-card + source timestamp/history and linked companion replies: private Android branch commit `032c3737fcd7fab5022c3db0920f6c331653bf96`, test build `2026092824`. Still excludes original signing material, private backend, saved user data and deployment configuration.

P0-3 build-only test branch ci/p0-3-timechain-20260928: copies only TimeChainScreen, PresenceApp, PresenceRoute from private Android branch feat/p0-3-android-timechain-20260928; preserves P0-2 v25 source and existing test signing. Test versionCode 2026092826; separate tag android-p0-3-acceptance-20260928; excludes runtime server, credentials, captures and user entries.

P0-3 simplified unlock attribution: screen unlock/present defaults to user, no separate Runtime identity inference; retain raw OS events and legacy protocol aliases. Mobile source e8a955e2dc6dde026e1c3608b8fad9cf2e344909; test build 2026092827. Only Android source copied to public mirror. No credentials, captures or user data.

2026-09-28 compact P0-3 test build 2026092828: top-12 existing heartbeat usage totals + at most one original-hour bounded aggregate per heartbeat on the existing /api/activity/events idempotent endpoint. App's TimeChain opens in diary/reply mode and defers raw Android evidence loading until the technical tab. Source feat/p0-3-compact-behavior-20260928 (Android only), no secrets, private server code, screenshots or personal messages copied.

2026-09-29 native delivery receipt build 2026092902: synced Android Kotlin sources from private branch `fix/android-receipts-20260929` (based on installed QAvatar 2026092901). Adds exact-event screenshot late reconciliation and explicitly staged phone notification receipts, including durable tap/reply interactions. Retains existing launcher/UI, Android user data, and signing-safe mirror configuration. No private runtime server, credentials, screenshots or conversation data copied.

2026-09-29 follow-up build 2026092903: copied only Android `NativeRuntimeService.kt` and host contract test from private `fix/android-receipts-20260929` to return the immutable capture event UUID immediately, upload the same queued image asynchronously, and let chat fetch it later. No screenshots, credentials, user data or backend code included.
