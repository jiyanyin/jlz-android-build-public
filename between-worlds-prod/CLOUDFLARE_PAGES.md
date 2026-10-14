# World Between · Cloudflare Pages deployment

This file is deployment configuration only. It does not change the accepted UI.

## Git source

- Repository: `jiyanyin/jlz-android-build-public`
- Production branch: `main`
- Root directory: `between-worlds-prod`

## Build

- Framework preset: Vite (or None with the explicit command below)
- Build command: `npm run build`
- Build output directory: `dist`
- Node: 22

## Runtime configuration

Optional public build variable:

- `VITE_RUNTIME_URL`: preferred Runtime base URL.

If it is unset, the client temporarily falls back to the legacy Render Runtime.
The Web token remains user-entered and browser-local; it must never be stored as
a Pages build variable.

## Migration acceptance

Before changing Android WebShell:

1. Pages root opens successfully.
2. PWA manifest, icons and service worker load on the Pages origin.
3. Existing Mist/Gothic UI and navigation are unchanged.
4. With Runtime unavailable, the page still opens in local/offline mode.
5. With a valid Runtime URL/token, queued records can reconnect and sync.
6. Do not remove the legacy Render site until the new host and Android WebShell
   endpoint have both passed acceptance.
