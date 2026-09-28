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
