# Stage E · App Hub

Status: implemented on `feature/world-between-control-v1-20261003`.

## Goal

World Between stays an ordinary Android app, not a replacement system launcher.

Stage E adds a **soft start hub** inside the existing WebShell Home:

- learning shortcuts are visually first;
- a few user-pinned apps appear on Home;
- a full App Drawer remains one tap away;
- installed-app inventory stays on-device;
- entertainment apps are not removed, only visually deprioritized.

This prepares the surface for Stage F unlock soft-gate logic without making Stage E itself a lock or enforcement layer.

## Home surface

A new **先从这里走 / APP HUB** card is placed directly after the Runtime status banner and before DailyPlan.

The default visible path is:

1. 伴读 — fixed first-party study shortcut;
2. 粉笔 — first pinned native app when installed;
3. ChatGPT — second pinned native app when installed;
4. one additional user-pinned app, normally 微信 on a fresh install;
5. ＋ 全部 — opens the App Drawer.

The Home grid uses compact glass tiles with typographic monograms instead of transporting every Android icon into the WebView.

Reason:

- avoids a large base64 icon bridge;
- keeps memory / rendering cost low;
- does not upload app icons or inventory;
- visually matches World Between instead of looking like a stock Android launcher.

## App Drawer

The drawer is a World Between bottom sheet, not a second launcher screen.

It contains:

- fixed 伴读 shortcut;
- local search by app name / package / category;
- all Android launchable apps except World Between itself;
- learning / communication / other / entertainment & shopping / system grouping;
- pin / unpin from Home;
- move pinned apps forward / backward.

Pinned apps float to the top inside their category.

Entertainment and shopping apps remain accessible; Stage E does not block or time-limit them.

## Native bridge

The owned Android WebShell exposes a narrow JavascriptInterface named:

`WorldBetweenAppHub`

It supports:

- read local launcher snapshot;
- launch a launcher-visible app;
- pin / unpin;
- reorder pinned apps;
- hide / restore Home visibility;
- open Banduread.

Security boundary:

- only the owned WebShell receives this bridge;
- `launch()` accepts only apps Android reports through the launcher query;
- World Between cannot launch itself through the bridge;
- the app list is returned only to the local WebView;
- no Runtime endpoint receives the installed-app inventory.

## Launcher repository

Stage E upgrades old unordered pin storage to ordered pins.

Fresh default order:

1. `com.fenbi.android.servant`
2. `com.openai.chatgpt`
3. `com.tencent.mm`

Older installs migrate their existing pinned set into the ordered model without losing pins.

Pinning an app automatically removes any legacy Home-hidden flag so a user never sees “已固定” while the app remains absent from Home.

## Categories

Local category hints are intentionally simple:

- 学习;
- 通讯;
- 其他;
- 娱乐与购物;
- 系统.

They are visual ordering hints, not behavior policy.

Stage G will own actual entertainment gate policy.

## Native fallback

The existing native App Drawer remains available and now uses the same ordered pin model.

Its long-press actions support:

- pin / unpin;
- move forward;
- move backward;
- hide / restore.

The WebShell App Drawer is the primary Stage E user surface.

## Compatibility

Stage E does not change:

- Study Session timing;
- DailyPlan;
- Runtime credentials;
- Focus / Entertainment Gate behavior;
- system HOME role;
- app lock policy.

No request is made to become Android’s default launcher.

## Build

Android version:

- versionName: `0.4.0-webshell.5`
- versionCode: `2026100305`

## Acceptance

1. Home shows App Hub before DailyPlan.
2. Banduread is always first.
3. Fresh installs prefer Fenbi then ChatGPT before non-study apps.
4. Home apps come from the real installed-app list on Android.
5. Browser/PWA mode does not pretend it can enumerate native apps.
6. App Drawer shows every launcher-visible app except World Between.
7. User can pin, unpin and reorder Home apps.
8. Installed-app inventory never goes to Runtime.
9. Old unordered pin preferences migrate safely.
10. Existing hidden entertainment app can be pinned and becomes visible.
11. Web App Hub tests pass.
12. Android launcher preference tests pass.
13. Web production build passes.
14. Android unit tests / APK build / signing pass.
15. PR build does not publish the latest APK.
16. No production merge or deployment occurs during Stage E.
