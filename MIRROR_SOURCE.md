# Mirror provenance

This repository is a build-only public mirror of the Android client.

P0-2 test source snapshot: private Android branch `feat/p0-2-android-between-20260928`, commit `6342857d44485d3b118c59d484cd58cadb906a1b` (application source only).
This feature is isolated on public branch `ci/p0-2-between-android-20260928`, not public `main`.
Private source signing configuration is replaced with the public mirror's existing signing-safe Gradle file, with test versionCode `2026092822`.
A separately configured GitHub Actions secret provides the existing stable signing certificate at build time.
The workflow only publishes a test release tag; it never overwrites `android-native-latest`.

Excluded by design: private Git history, Runtime/MCP/server sources, keystores, credentials, captures, user data, environment files, and private deployment configuration.
