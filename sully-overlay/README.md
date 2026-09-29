# World Between / SullyOS Glass Preview — 2026-09-29

## Scope and provenance
This is a **review-only branch** based on the already-in-use native versionCode
2026092903 source, branch ci/qavatar-smart-capture-v1-20260929.
It is not a main-branch merge or a production release.

The original SullyOS source is fetched **only at CI build time** from
https://github.com/qegj567-cloud/SullyOS pinned commit
eecf15fff33adeb7c7e0181226e9225e5d5fc043. It is not copied into this
GitHub mirror. Compiled assets and the required LICENSE are packaged together.

License: PolyForm Noncommercial License 1.0.0, Required Notice:
Copyright (c) 2024–2026 NMJ (SullyOS / 手抓糯米机).
Personal/noncommercial experiment only. Preserve the license and notices.
No upstream author endorsement is implied.

## Reuse rather than a remake
- The actual SullyOS React UI, launcher/page components and appearance app are
  fetched from pinned upstream and built without reconstructing them.
- Only a cherry-pink / dusty-blue CSS overlay, launcher rename patch,
  and a fixed native routing adapter are maintained here.
- Tailwind gets precompiled offline instead of loading its CDN at runtime.
- The existing JLZ Compose home, Runtime, screenshot queue, overlay,
  accessibility and notification components remain untouched.
- The debug preview package is dev.jlz.presence.sullylab. Installing it does
  NOT replace dev.jlz.presence or its application data.

## Tiles wired to actual native routes

| Sully tile | Existing native destination |
| --- | --- |
| ChatGPT | Installed com.openai.chatgpt app |
| 状态灯 | Between(status) |
| 时间链 | TimeChain |
| 手机轨迹 | Timeline |
| 你我之间 | Between(moments) |
| 伴读 | Study |
| 设备状态 | Diagnostics |
| 权限检查 | PermissionDoctor |
| 外观 | SullyOS built-in Appearance (isolated preview data) |

No arbitrary package names, links, shell calls, credentials, phone contents,
Runtime tokens, accessibility commands or screenshots pass the JavaScript
bridge. Nonlocal network resources are blocked in the preview WebView.

## Build and acceptance
GitHub workflow: .github/workflows/sully-glass-ui.yml.
Artifact name: World-Between-Sully-Glass-Preview.
The workflow NEVER updates the production android-native-latest release.

Device checks:
1. Install the test APK alongside the live dev.jlz.presence app.
2. Open the test app, tap "打开糯米机桌面（试验）".
3. Inspect the genuine pink-blue Sully desktop; swipe, open Appearance, return.
4. Click every renamed app icon and verify the native destination is correct.
5. Check the production app remains unaffected and its existing data remain.
6. Measure actual screen performance on phone and tablet before any promotion.

Status: SOURCE IMPLEMENTED, CI BUILD IN PROGRESS, DEVICE NOT YET VERIFIED.
Some inherited SullyOS functions require internet or remote APIs and are
deliberately unavailable in this offline preview. It is not a production
backend integration.
