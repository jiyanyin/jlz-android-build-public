# World Between owner audit — 2026-10-15

Scope: current Android PR #51 head, Runtime PR #102 head, and the existing Windows Home Node. A source implementation, a green build, a running service, and a phone display receipt are distinct evidence levels.

## Current path

- Android package: `native-android`, with the `between-worlds-prod` Vite app copied into private APK assets at build time. The WebShell opens `between-worlds-prod.pages.dev` and intercepts packaged files for offline use.
- Web Runtime default: the private Home Node `/runtime` URL. Native WebShell requests use the Android Home bridge when configured. The old `world-between-web` source was not copied into the APK and had its own GitHub Pages workflow and old Render default. Its source and workflow are removed in this branch; historical code remains recoverable in Git.
- Windows Home Node: Runtime on loopback 17860, MCP on 17861. MCP points to the local Runtime in the current private config. Production device IDs are exactly the canonical phone and tablet IDs; next-hour messages default to the phone, so the scheduled task does not need to discover a target every time.
- Official connector: deployed MCP declares 107 tools, while the existing Codex window lists 97. The ten newly declared tools require discovery in a fresh connected session. The affected ChatGPT scheduled task's actual catalog and raw calls remain unobserved.

## Feature status by requested area

| Area | Code present | Remaining acceptance or gap |
| --- | --- | --- |
| Location | One-shot `get_current_location`; separate opt-in trip stream and `read_live_location` | Continuous movement awareness requires active trip and fresh samples; one-shot is not a movement subscription. |
| Video-style CoWatch | Selected-app screen sampling, about one frame per five seconds, up to ten minutes | GPT must read frames during the session; device and official-chat acceptance remains. |
| Voice-call window | Native callback activity and call command, with lock-screen/turn-screen-on flags | Forced full-screen behavior on the owner's devices is not verified. |
| Focus/sleep display | Native DailyMode activity and Q assets | Device-specific lock-screen rendering and Q appearance need cover-install acceptance. |
| Local reminders | Battery, entertainment, screen, steps, weather, light, shake and local copy/World Content hooks | Sensor/permission availability and exact notification display remain device checks. |
| Dynamic copy | Voice state engine and World Content cache/write path | A broad replacement of every old line has not been audited. |
| Official mode control | `native_daily_mode_action` and Android executor | Official connector discovery plus device execution receipt required. |
| Automatic captures | Opt-in coordinator for app switch/stay/scroll, byte-budgeted with no frame-count cap | Not every screen is capturable; pixel viewing by the official task is separate. |
| Timed messages | Runtime queue, one-hour helper and receipts; `schedule_native_presence_plan` remains for phone-side conditional/call plans | Automatic ChatGPT wake enqueue has not been verified. Manual two-minute delivery was user-confirmed. |
| Huawei health | Notification step count and visible-page sleep parsing with date evidence | Automatic 08:05 sleep read is not proven on device. |
| Behavior evidence | Dual-device patrol, context pack, daily behavior evidence and timeline tools | Coverage and inference quality require data/official-task acceptance. |
| Echo, calendar, life and study | Date-based Echo, newest-message dock fix, calendar, timed life reminder and current-day study display code | Long-history navigation and physical UI acceptance remain. |

## Cleanup boundary

The removed `world-between-web` was a separate old GitHub Pages frontend, not the current APK frontend. This repository change does not delete remote deployments, browser localStorage, Home Node data, prior APKs, private Q assets, old Git branches or Render services. Those need a separate live dependency inventory before retirement. Historical docs and compatibility behavior must not be interpreted as an active data path merely because they contain old hostnames.

## Immediate blocker

The affected automatic task reported `invalid_plan_step` from the older phone-side `schedule_native_presence_plan` path, then a separate upstream safety block. The current Runtime branch adds exact field reporting and defaults for omitted legacy step ID, expiry and text placement. The preferred short path remains `schedule_next_hour_presence_bundle` in a freshly discovered connector. Neither source fix proves the upstream safety block is gone; the acceptance test is an unattended official wake that schedules a future message and receives a due-time receipt.
