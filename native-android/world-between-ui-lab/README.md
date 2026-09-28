# 世界之间 V2 · Original UI preview

- Branch: world-between-v2-ui-20260928
- Standalone Android package: `dev.jlz.worldbetween.uilab`
- Status: P0/P1 engineering preview, **not the installed World Between client**.
- Original HTML/CSS/JS + hand-authored SVG scene; no copied IB code/assets.
- Offline WebView only; no INTERNET, location, notifications, accessibility, or app launcher permissions.
- All timeline entries/notes in the preview are ephemeral; data is NOT synced.
- Every potentially functional screen declares "交互演示" until connected/verified in P2+.
- Build: `gradle :world-between-ui-lab:assembleDebug` after checkout.
- UI source: `src/main/assets/index.html`; edit CSS design tokens under `:root`.

Do not merge the lab module to the production `main` or replace any production APK. After design approval, transport the proven presentation components to the latest production native source and integrate one functionality at a time, with per-device testing.

## Manual visual acceptance
- Portrait phone: no horizontal overflow and readable primary task.
- Scroll through cards and dock; drawer/timeline/tab interactions behave.
- Bottom half-screen recorder opens, records a *preview* start, displays a running timer, ends it and shows one synthetic event.
- Backfill/real device actions are not enabled; no deceptive success state.
- Landscape/tablet responsive and keyboard-safe modal.
- Uninstall preview independently from the production app.
