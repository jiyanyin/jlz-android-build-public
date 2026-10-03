# Stage F · Unlock Soft Gate

Status: implemented on `feature/world-between-control-v1-20261003`.

## Goal

After a real device unlock, bring World Between to the foreground **once**, then get out of the way.

This is intentionally a soft gate:

- World Between is not the system launcher;
- the Android lock screen remains untouched;
- Back / Home / gesture navigation still works normally;
- there is no loop that keeps dragging World Between back to the front;
- Stage F does not lock entertainment apps or enforce usage limits.

Stage G remains the entertainment-gate stage.

## Trigger path

The existing World Between accessibility service registers a dynamic receiver while the service is connected.

It listens for:

- `ACTION_USER_PRESENT`;
- `ACTION_SCREEN_OFF`.

`ACTION_USER_PRESENT` is the one-shot trigger after the keyguard has actually been dismissed.

The coordinator waits 520 ms so the unlock transition can settle, then evaluates safety conditions and, if allowed, opens:

`WebShellActivity -> https://between-worlds-prod.onrender.com/?shell=android&entry=unlock`

The WebShell uses the existing World Between app task instead of becoming Android HOME.

## Why AccessibilityService is used

Modern Android restricts background activity launches.

World Between already has a user-enabled accessibility service for its existing phone-observation and focus-gate features. Stage F reuses that one service as the bounded system integration point.

It does **not**:

- watch foreground changes and repeatedly force World Between back;
- press Home;
- simulate taps;
- abuse accessibility actions to trap the user.

It reacts only to the unlock broadcast and makes one foreground request.

## One-shot policy

The gate is eligible only when:

- the feature is enabled;
- the display is interactive;
- keyguard is no longer locked;
- World Between is not already the known foreground package;
- at least 8 seconds have passed since the previous presentation;
- the foreground surface is not safety-sensitive.

A repeated / duplicate USER_PRESENT inside the cooldown is ignored.

Screen-off cancels a still-pending delayed presentation.

## Safety skips

Stage F explicitly skips known phone, emergency, camera and alarm-clock surfaces, including package patterns such as:

- dialer / InCall UI;
- emergency UI;
- Xiaomi / AOSP / Google camera;
- Xiaomi / AOSP / Google / Samsung clock.

This means an unlock into an active call, camera action or alarm is not intentionally covered by World Between.

## Web landing behavior

Manual app launch keeps the existing invitation / welcome behavior.

Unlock launch uses `entry=unlock` and:

1. bypasses the invitation overlay;
2. lands directly on the real Home;
3. briefly changes the App Hub header to:
   - `UNLOCKED · 先看我一眼`
   - `解锁了。先决定你现在要去哪。`
4. removes the transient `entry` query parameter after 8 seconds.

The Home therefore exposes, immediately:

- current time + JLZ voice card;
- App Hub;
- DailyPlan;
- Study Session;
- the rest of World Between.

## User control

Native Settings and Permission Doctor now include:

**解锁后先看我**

The switch is on by default for this requested Stage F behavior.

The panel explains that:

- it presents only once per unlock;
- it is not a replacement launcher;
- Back / Home / swipe exits normally;
- Accessibility permission is required.

The user can disable the feature at any time.

## Permission Doctor

The obsolete “默认桌面” diagnostic item is removed.

It is replaced with the real Stage F dependency:

- `解锁后先看我`
- OK when enabled + Accessibility is active;
- MISSING when enabled but Accessibility is off;
- LIMITED when the soft gate itself is disabled.

## Privacy

Unlock gate state is local.

Stored values:

- enabled / disabled;
- last presentation timestamp.

The installed-app list still follows Stage E and remains device-local.

A successful unlock presentation records a local timeline event so the existing phone timeline can explain why World Between appeared. No new screenshot or sensitive lock-screen content is collected by Stage F.

## Failure behavior

If Android / OEM policy refuses the foreground launch:

- the device remains usable;
- there is no retry loop;
- Stage F waits for the next real unlock.

This is deliberate. A soft gate is allowed to fail softly rather than trying to overpower Android.

## Android build

- versionName: `0.4.0-webshell.6`
- versionCode: `2026100306`

## Acceptance

1. Real USER_PRESENT can request World Between once.
2. Duplicate unlock delivery is cooldown-protected.
3. Disabled soft gate never launches.
4. Locked / non-interactive state never launches.
5. World Between foreground does not relaunch itself.
6. Phone / emergency / camera / alarm surfaces are safety-skipped.
7. No HOME role is required.
8. No accessibility relaunch loop exists.
9. Unlock entry lands directly on Home.
10. Manual app launch keeps the invitation flow.
11. App Hub shows a transient unlock greeting.
12. User has a native enable / disable switch.
13. Permission Doctor exposes the real dependency.
14. Policy unit tests pass.
15. Web TypeScript / production build passes.
16. Android unit tests / APK build / stable signing pass.
17. PR build does not publish latest APK.
18. No production merge or deployment occurs during Stage F.
