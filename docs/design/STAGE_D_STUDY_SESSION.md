# Stage D · Study Session

Status: implemented on `feature/world-between-control-v1-20261003`.

## Goal

Turn “学习” from a static shortcut into one durable session system shared by:

- the Android native timer;
- the World Between WebShell Home card;
- Runtime;
- the official ChatGPT read layer;
- Banduread / question events.

The Android device owns real timing. Runtime owns cross-device reflection and daily aggregation. The WebShell displays the same state without needing to remain open.

## Native source of truth

`StudySessionRepository` remains the phone/tablet source of truth for an active session.

A session supports:

- start;
- pause;
- resume;
- finish.

Persisted fields include:

- `sessionId`;
- active / paused;
- session start time;
- active segment start;
- accumulated pause time;
- configured target packages.

Paused time does not count toward effective elapsed time.

The timer is **not forced to 25 minutes** in the Stage D UI. The user decides when to stop. The older 25/5 patrol controller remains available internally but is no longer the primary Study screen.

## Native study screen

The Stage D native Study screen shows:

- today’s cumulative effective time;
- current-session elapsed time;
- completed-session count;
- current running / paused / idle state;
- start / pause / resume / finish;
- Banduread shortcut;
- Fenbi shortcut.

Visual direction follows World Between:

- glass card hierarchy;
- deep blue / violet base;
- muted rose pause accent;
- restrained typography;
- one dominant timer rather than a dense dashboard.

When opened from the WebShell through `jlz://native/study`, the explicit back control returns to the WebShell rather than dropping the user into the legacy native Home.

## Ongoing notification

`StudyTimerService` remains the durable foreground delivery layer.

It provides:

- a quiet ongoing timer notification;
- pause / resume;
- finish;
- chronometer behavior that continues while the WebShell is not visible.

Notification actions update the same repository and report the same canonical lifecycle events to Runtime.

## Runtime event contract

Stage D normalizes lifecycle aliases:

- `start` / `study_started` -> `study_started`;
- `pause` / `study_paused` -> `study_paused`;
- `resume` / `study_resumed` -> `study_resumed`;
- `finish` / `study_finished` -> `study_finished`;
- `question_answered` remains informational.

Native metadata may arrive nested under `metadata`; Runtime promotes those fields into the canonical study event.

Each native lifecycle event carries:

- session ID;
- event timestamp;
- local date;
- timezone offset;
- source surface.

Finish additionally carries:

- total session milliseconds;
- unpaused milliseconds;
- effective study milliseconds.

## Runtime persistence

Runtime persists:

1. current state per device under the existing `study` state;
2. finished session history under `study_sessions`.

Finished sessions are idempotently replaced by `session_id`, preventing retry duplicates.

## Daily aggregation

`GET /api/study/summary?date=YYYY-MM-DD` returns authenticated companion metadata.

`GET /api/web/study/summary?date=YYYY-MM-DD` exposes the same safe summary to the low-privilege Web client.

The summary includes:

- finished effective study time;
- currently active effective time;
- total effective study time today;
- completed session count;
- active session count;
- current device/session;
- paused state;
- current module / subject;
- Banduread answer counts;
- recent finished sessions.

Phone and tablet are aggregated together. If both have active sessions, the total reflects both.

## WebShell Home card

Home adds a dedicated **陪你学一会儿 / STUDY SESSION** card.

It shows:

- a large HH:MM:SS daily timer;
- live local ticking from the latest Runtime snapshot;
- completed rounds;
- current round timer;
- current device/module;
- Banduread question count;
- LIVE / PAUSED / READY state;
- a single primary control that opens the native Study screen.

The Web client does not receive Android control credentials. It reads safe summary metadata and deep-links into the native control surface.

While a session is active, the WebShell refreshes the tiny summary every 15 seconds; the visible clock itself advances locally every second. When idle, normal 60-second foreground sync is sufficient.

## Official GPT

MCP adds:

- `read_study_summary(date)`

This is intentionally read-only in Stage D. The later hourly official-GPT policy can inspect study state without automatically starting or stopping a session.

## Compatibility

Existing Banduread `question_answered` events continue updating question counters.

Informational study events no longer create pointless Android commands. Only lifecycle events are eligible for device command echo.

Android accepts both legacy lifecycle words and the new canonical `study_*` names.

## Acceptance

1. Native session survives page changes because timing is not owned by the WebShell.
2. Start / pause / resume / finish all use one persisted native repository.
3. Paused time is excluded.
4. Finished metrics persist in native SQLite.
5. Runtime stores finished sessions idempotently.
6. Runtime produces cross-device daily totals.
7. WebShell Home displays the live daily/session state.
8. WebShell -> native Study -> back returns to WebShell.
9. Banduread informational events remain compatible.
10. Official GPT can read the daily summary.
11. Web Study Session tests pass.
12. Runtime Python + MCP regressions pass.
13. Android build/sign/artifact regressions pass.
14. No production merge, deployment, or latest APK publication occurs during Stage D.
