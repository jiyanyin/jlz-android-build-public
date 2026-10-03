# Stage G · Entertainment Gate V2

Status: implemented on `feature/world-between-control-v1-20261003`.

## Goal

Replace the old passive “open app → reminder → later lock” chain with one explicit, local, call-style gate session.

Stage G is the first real behavioral gate:

1. detect entry into a configured entertainment / shopping app;
2. move the app out of the foreground;
3. show a full-screen **纪临洲来电** page;
4. user chooses whether to answer;
5. after answering, user states the intent for this visit;
6. Gate V2 chooses a short release window;
7. one-minute warning;
8. expiry returns to the call gate;
9. optional **先做一小步** path requires 3 additional effective Study Session minutes before release.

This remains a soft, user-controlled system. It does not permanently prevent access and it does not alter Android lock-screen security.

## Profiles

Feed / infinite-scroll apps:

- 小红书 — `com.xingin.xhs`
- 抖音 — `com.ss.android.ugc.aweme`
- 快手 — `com.smile.gifmaker`
- 哔哩哔哩 — `tv.danmaku.bili`
- 微博 — `com.sina.weibo`
- 今日头条 — `com.ss.android.article.news`
- 知乎 — `com.zhihu.android`

Shopping apps:

- 拼多多 — `com.xunmeng.pinduoduo`
- 淘宝 — `com.taobao.taobao`
- 京东 — `com.jingdong.app.mall`
- 闲鱼 — `com.taobao.idlefish`

ChatGPT, DeepSeek, Banduread, Fenbi and communication apps are not entertainment profiles.

## Gate state machine

### 1. INCOMING

When an entertainment profile becomes the foreground app with no active release:

- Accessibility performs one `GLOBAL_ACTION_HOME`;
- `EntertainmentGateActivity` appears;
- avatar = the current JLZ chat avatar;
- page uses a dark navy / mist-violet / muted rose call-screen treatment;
- the avatar has a restrained pulse ring;
- actions:
  - **先不去**
  - **接通**

The gate activity is `noHistory` and excluded from Recents.

Pressing Back is equivalent to declining the gate. Because the entertainment app was moved out of foreground first, declining leaves the user outside that app.

### 2. CONNECTED

After **接通**, the page asks:

**告诉我。你进去干什么？**

Available paths:

#### 我有明确目的

Examples:
- find one item;
- look up a specific post;
- buy / check a product;
- deal with one concrete task.

Release:

| Device | Feed | Shopping |
| --- | ---: | ---: |
| Phone | 5 min | 10 min |
| Tablet | 8 min | 12 min |

#### 我就是想休息一下

Release:

| Device | Feed | Shopping |
| --- | ---: | ---: |
| Phone | 5 min | 6 min |
| Tablet | 8 min | 10 min |

#### 我现在就是想进去

The system does not force the user to invent a “productive” reason.

Release:
- phone: 3 min;
- tablet: 5 min.

This is the shortest release, but it remains an explicit escape path.

#### 先做一小步再进去

Stage G snapshots today’s current local effective Study Session time.

It stores:

- package;
- baseline effective study milliseconds;
- required delta = 3 minutes;
- creation time.

The gate then opens the existing native Study Session page.

On the next entertainment attempt:

- Gate V2 compares current effective study time with the stored baseline;
- unfinished work shows the remaining approximate minutes;
- reopening Study does **not** reset the baseline;
- once +3 effective minutes are reached, the user gets:
  - phone: 5 min;
  - tablet: 8 min.

The pending requirement is cleared only after a release is granted.

## “接通后我真的说话”

Choosing PURPOSE / BREAK / DIRECT does not immediately throw the user into the target app.

The connected page first renders a JLZ response line, then shows the actual release button:

**进去 · N 分钟**

This preserves the intended call flow:

**来电 → 接通 → 回答 → 我回应 → 放行**

## Sentence matrix

The old reminder-only message bank is removed.

Gate V2 owns one sentence matrix built from short clause combinations.

It covers:

- incoming call;
- purpose response;
- rest response;
- direct-entry response;
- small-step instruction;
- small-step complete;
- small-step incomplete;
- one-minute warning;
- expired release.

The current matrix has **500+ distinct rendered lines** while keeping individual lines short enough for the full-screen call UI.

This is local and does not require an LLM round trip.

## Release session

A release stores:

- package;
- gate session ID;
- user intent choice;
- granted timestamp;
- expiry timestamp.

Release state is persisted in `SharedPreferences`.

Opening the same app again inside the release window does not show another gate.

Leaving and re-entering the app does not reset the window; the original expiry remains authoritative.

## HyperOS presentation fix

Phone RC1 proved that the gate detector itself fired — Xiaohongshu was pushed out of the foreground — but HyperOS could complete the HOME transition after the gate Activity had already been started, leaving only the launcher visible.

RC2 changes the order to:

1. detect entertainment entry;
2. request HOME;
3. wait 320 ms for the launcher transition to settle;
4. launch `EntertainmentGateActivity` directly from the bound AccessibilityService context.

The gate launcher now fails softly if an OEM refuses the Activity start, and duplicate pending presentations for the same package are suppressed.

## Warning and expiry

The coordinator schedules local timers from the release session:

- at T−60 s: one JLZ notification;
- at expiry:
  - if the target app is still foreground, it is moved home and the call gate reappears;
  - if the app is no longer foreground, nothing interrupts the user;
  - the next later entry into that app will gate normally.

A service restart may reconstruct the release from persisted state on the next accessibility event.

## Phone vs tablet

The same APK detects tablet using `smallestScreenWidthDp >= 600`.

Phone remains stricter because it is the higher-risk habitual-scroll device.

Tablet gets longer release windows but is not exempt from the gate.

## Local-only critical path

Gate decisions do not wait for Runtime or official ChatGPT.

Reason:

- app entry needs sub-second response;
- network / Render / Runtime failures must not break phone navigation;
- the user already approved local policy defaults.

The official ChatGPT remains the long-horizon brain through normal phone timeline and later scheduled review.

Gate decisions and choices are written to `LocalLifeStore` so the existing timeline can surface:

- gate decline;
- chosen intent;
- release minutes;
- release expiry;
- small-step requirement.

No new screenshot is taken by Stage G.

## One chain only

The previous:

- `EntertainmentGuard`
- `EntertainmentMessageBank`
- old NUDGE / FIRM / LOCK threshold chain

is removed from the active codebase.

Stage G has one entertainment path:

`PresenceAccessibilityService -> EntertainmentGateV2Coordinator -> EntertainmentGateActivity / EntertainmentGateRepository`

Existing `FocusRepository` / `FocusGateActivity` remains separate and higher priority for explicit Focus or manually locked apps.

## User settings

Native Settings and Permission Doctor expose:

**娱乐来电门禁**

Default: enabled.

The card shows:

- current device policy;
- phone / tablet distinction;
- purpose / break / direct release lengths;
- 3-minute small-step rule;
- one-minute warning behavior.

The user can pause Gate V2 locally at any time.

## Safety / non-goals

Stage G does not gate:

- dialer / emergency UI;
- camera;
- alarm clock;
- banking;
- authentication;
- system settings;
- ChatGPT;
- Study apps.

Those are either outside the entertainment profile list or already protected by Stage F safety handling.

Stage G also does not:

- become the system launcher;
- intercept lock-screen authentication;
- repeatedly relaunch itself on every foreground event;
- use Runtime latency on the blocking path.

## Android build

- versionName: `0.4.0-webshell.8`
- versionCode: `2026100308`

## Acceptance

1. First entertainment entry with no release shows call gate.
2. Decline leaves the entertainment app out of foreground.
3. Answering exposes intent choices.
4. Choice first shows JLZ response, then release button.
5. Release duration differs by intent and device type.
6. Same app stays ungated until that release expires.
7. One-minute warning fires once per local release session.
8. Expiry re-gates only if the app is still foreground.
9. Direct-entry path always exists.
10. Small-step baseline is not reset when reopening Study.
11. +3 effective Study Session minutes unlock the small-step release.
12. Gate state persists locally across Activity recreation / process gaps.
13. Gate can be disabled locally.
14. Legacy entertainment reminder / lock chain is removed.
15. Sentence matrix contains at least 500 distinct lines.
16. Focus hard-gate logic still has priority.
17. Android policy + repository tests pass.
18. Web regressions remain green.
19. Android APK build / stable signing / artifact pass.
20. Latest APK publication remains skipped on PR.
21. No production merge or deployment occurs during Stage G.
