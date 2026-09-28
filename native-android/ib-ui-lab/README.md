# World Between / IB UI Lab (isolated Android proof of concept)

**Status:** experiment branch only; not production, and not yet device-verified.

This is a separate Android application (`dev.jlz.ibuilab`) that loads the **unaltered upstream InternalBeyond-Mobile UI** in an Android WebView, using WebViewAssetLoader. It is not an update to the installed `dev.jlz.presence` app and does not replace the phone's launcher.

## Provenance and licenses

- Upstream: https://github.com/Sui-IB/InternalBeyond-Mobile
- Pinned upstream commit: `040bf2a57d3f4efb2a79689a29a95086d6b8b1be`
- Upstream code: PolyForm Noncommercial License 1.0.0 (copyright © 2025–2026 Sui).
- Upstream imagery / documentation: CC BY-NC-SA 4.0, where its author can license it.
- The GitHub Actions workflow obtains upstream assets at build time from that **fixed commit**, and bundles `LICENSE` and `COPYRIGHT.md` into the APK. Preserve attribution and licenses in any derivative. Personal/noncommercial study only; no commercial distribution.
- Upstream name/logo/screens remain solely for testing and may not imply official affiliation. Neither this lab nor its APK is an official IB build.

## Design safety

- Separate APK and application ID, no change to existing World Between app.
- No `addJavascriptInterface`, no Android device-control bridge, no screenshots, no accessibility integration, no Runtime authentication, no server credentials.
- No INTERNET permission: third-party URLs are blocked. Google Fonts will fall back to the device font. This first pass does **not** evaluate final fonts or online functions.
- Android WebViewAssetLoader exposes only bundled `/ib/` files as a secure HTTPS origin.
- Original upstream content remains unmodified so layout and gesture comparisons are meaningful.
- Copying UI does not transfer ownership of the original design.

## Build and install

CI: `.github/workflows/ib-ui-lab.yml` builds `:ib-ui-lab:assembleDebug` and uploads a **non-production, separately installable** debug APK artifact. No release or latest-APK update.

Local (after downloading pinned assets as the workflow does):

```sh
cd native-android
gradle :ib-ui-lab:assembleDebug
```

The launcher icon is the default Android icon; it is intentionally separate from World Between. The UI is served from `https://appassets.androidplatform.net/ib/index.html` internally.

## Device acceptance (not yet run)

1. Install `dev.jlz.ibuilab` alongside the production application; ensure the production launcher/data are unchanged.
2. Open offline: verify full-page render, desktop cards, color panels, glass effects, clock, dialogs, scroll and long-press drag on the actual phone and in landscape/tablet orientation.
3. Inspect logcat `IB_UI_LAB` for DOM/load/paint durations, then measure smoothness by actual swiping and tapping.
4. Confirm external links/online model calls cannot navigate/load; no camera/microphone/device data can be accessed. Fonts should fall back offline; compare typography separately when local licensed fonts are bundled.
5. Uninstall test app; confirm original phone launcher, Runtime, permissions, screenshot and study systems remain intact.

**Do not promote this branch to main or publish as the production APK before explicit acceptance.**
