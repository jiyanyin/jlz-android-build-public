# App gate revision — October 16, 2026

Owner-approved policy for `0.6.5-world-between-local.5 / 2026101603`, continuing PR #51. This supersedes the former ten-question entertainment / five-question ordinary policy. Runtime PR #102 and the deployed Windows services require no code change for this local policy.

| App use | Correct answers | Per-app arithmetic exemption after passing |
| --- | ---: | ---: |
| Work, study, ordinary tools (including ChatGPT, WeChat and VPN tools) | 3 | 60 minutes |
| Entertainment feed / games | 5 | 10 minutes |
| Shopping | 5 | 30 minutes |
| Critical system, calls, SMS, camera, navigation, alarms, supported payment/authentication tools | 0 | No arithmetic gate |

The exemption begins when the last correct answer is submitted, uses a rolling duration rather than the clock hour, persists across service/process restarts, and is scoped to one package on one device. Returning from another app does not revoke or extend it. Wrong answers retry the current question. Old four-hour grants with five-second departure invalidation are ignored by the new grant schema; unrelated preferences and user records are retained.

Arithmetic is checked on a genuine app entry, not every accessibility window event. Keyboard, notification shade and permission/installer windows do not start a new visit. A continuous visit is not interrupted merely because the arithmetic deadline passes. Explicit Focus/Sleep and daily entertainment-rest policy retain their existing priority.

The existing entertainment time gate remains independent. Feed durations and weekly adjustment remain unchanged. Shopping releases are ten minutes for every intent on both device types; no weekly shopping shortening. A time-gate grant permits one immediate return to the already-started visit within thirty seconds without stacking another quiz. This consumes a one-use continuation, does not change the arithmetic deadline, and cannot create an unanswered arithmetic grant.

Shopping profiles: Pinduoduo, Taobao, JD, Xianyu and standalone Douyin Mall (`com.ss.android.ugc.livelite`; package confirmed against [Tencent MyApp](https://sj.qq.com/appdetail/com.ss.android.ugc.livelite)). The main Douyin app remains a feed profile. Shopping is excluded from the separate daily feed/game accumulation. Other existing feed profiles are Xiaohongshu, Kuaishou, Bilibili, Weibo, Toutiao and Zhihu. Games use Android metadata and the existing local classifier; unknown ordinary apps default to the work/tool arithmetic rule.

The answer field uses explicit deep-purple text/cursor on a light surface in both focused and unfocused states, with 24sp medium-weight digits and darker label/borders. Text-to-background calculated contrast is approximately 13:1. Device rendering remains to be checked.

## Verification and device state

Regression coverage includes all three exact expiry boundaries, app switching, repository recreation, rolling hours, per-app isolation, old-state migration, clock rollback, classification changes, shopping arithmetic versus time releases, one-use/expired time-gate continuation, transient windows and critical-app arithmetic bypass.

At takeover both PRs were open. Android main `a9837f3` was an ancestor of PR head `c10d996`; Runtime main `6215155` was an ancestor of PR head `a8d475f`. Windows remained at `0cb4706`, with Runtime/MCP healthy. The owner states the phone app has since been uninstalled. The last `.4` heartbeat is historical and offline, not evidence of a currently installed app. No install, pairing, permission grant, VPN change, merge or Runtime deployment is part of this fix.

Build and real-device acceptance are recorded separately after checks complete. Do not distribute an ordinary placeholder-resource CI APK as the private complete build.
