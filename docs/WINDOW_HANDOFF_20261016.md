# World Between — October 16 engineering handoff

Later owner-approved gate revision: [APP_GATE_POLICY_20261016.md](APP_GATE_POLICY_20261016.md), targeting `.5 / 2026101603`. Entertainment: five questions / ten-minute per-app arithmetic exemption; shopping: five / thirty minutes with independent ten-minute time gates; work/tools: three / sixty minutes. Explicit dark input text replaces theme-dependent colors. The owner has since uninstalled the phone app; `.4` device evidence below is historical. No reinstall or pairing is authorized by this revision.

The current private owner APK is **0.6.5-world-between-local.4 / 2026101602**, package `dev.jlz.presence`. APK source is commit `5087faf14f33252d7179f86cc7faa451fec09ff2`; later documentation commits do not change that artifact.

- Private CI [38832573357](https://github.com/jiyanyin/jlz-android-build-public/actions/runs/38832573357) passed unit checks, lint, assemble and stable-certificate signing. Authenticated artifact decryption and ZIP/resource checks passed.
- The owner phone reported the new exact client version on October 16, and the application UI opened with its existing device identity/connection. Tablet upgrade and comprehensive feature acceptance remain pending.
- The APK preserves the owner-selected avatar, 115 private Q poses and packaged offline WebShell. Private images/signing material/plaintext private APK are not committed to this public repository.
- Battery warnings now begin below 40%, then after each actual three-percentage-point drop from the last successful warning, suppressed during charging. This replaces the former sparse 40%/20% policy. Lifecycle and upgrade regression checks passed.
- Existing GPS reminders cover qualified home/office departure transitions; there is no dedicated arrival-home reminder. This release did not change location logic.

Read the [full private cross-repository handoff](https://github.com/jiyanyin/jlz-runtime-private/blob/work/final-owner-control-20261014/docs/window-handoff/CURRENT.md) with repository access. It records implemented vs. missing behavior, Windows deployment, owner-phone installation evidence, official scheduled-task delivery gaps, temporary transfer cleanup and next steps. See also the [requirement and enablement matrix](OWNER_REQUIREMENTS_AND_ENABLE_20261016.md).

Continue PR [#51](https://github.com/jiyanyin/jlz-android-build-public/pull/51) alongside Runtime PR [#102](https://github.com/jiyanyin/jlz-runtime-private/pull/102). Fetch current main and preserve both merged behavior and these unmerged additions; no reset, force push, automatic merge, downgrade, uninstall or data clearing. Phone version/launch confirmation is not acceptance of every feature or unattended official Task delivery.
