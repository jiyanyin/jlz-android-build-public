# 在场 · Native Android N0

Status: SOURCE-IMPLEMENTED / BUILD-UNVERIFIED / DEVICE-UNVERIFIED

This directory is the greenfield native Android client created under PD-v4.1-04.

It is physically separate from the legacy `android/app` tree and uses a new application id:

`dev.jlz.presence`

## N0 source currently present

- Kotlin + Jetpack Compose application shell
- local Runtime settings via DataStore
- Runtime API primitives for:
  - `X-Auth-Token`
  - `device_id`
  - `POST /api/device/state`
  - `GET /api/poll`
  - `POST /api/device/report`
  - `POST /api/screenshot`
- foreground Runtime poll/heartbeat service
- deterministic N0 device id: `android-phone-native-n0`
- local notification adapter
- Accessibility-backed screen observation adapter
- screenshot capture adapter boundary (capture implementation still pending)
- generated white-noise AudioEngine
- WearableBridge + normalized health model with per-field verification status
- first-person Presence copy

## Deliberately not claimed complete

- MediaProjection screenshot capture
- inline notification reply
- persistent overlay / Focus countdown
- RuntimeReplyEngine
- semantic place/weather
- audio.zip asset packaging and the unrecovered list of extra requested white-noise sounds
- complete HUAWEI Band health ingestion
- reboot/OEM durability
- real-device acceptance

A successful source commit is not a device PASS. N0 completion requires the separate real-device exit checks in `worker/n0/N0_NATIVE_CLIENT_ARCHITECTURE_v0.1.md`.
