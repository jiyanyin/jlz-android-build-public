#!/usr/bin/env python3
"""Static contract checks for the incremental World Between life action recorder.

Complements (does not replace) Gradle compilation and real-phone acceptance.
Does NOT create or modify any user's life event or Runtime state.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
src = root / "app/src/main/java/dev/jlz/presence"

repo = (src / "life/LifeActionRecorder.kt").read_text(encoding="utf8")
sheet = (src / "ui/LifeActionSheet.kt").read_text(encoding="utf8")
home = (src / "ui/PresenceApp.kt").read_text(encoding="utf8")
more = (src / "ui/ParchmentMoreScreen.kt").read_text(encoding="utf8")
chain = (src / "ui/TimeChainScreen.kt").read_text(encoding="utf8")
store = (src / "data/LocalLifeStore.kt").read_text(encoding="utf8")

for text in (
    'private const val PREFS = "world_between_life_action_v1"',
    'type = "life_action"',
    '.put("actor", "user")',
    '.put("source", "user_direct")',
    '.put("evidence", "manual_button")',
    '.put("needs_response", false)',
    '.put("session_id", sessionId)',
    'fun recordInstant(',
    'fun start(',
    'fun finish(',
    'private val guard = Any()',
    'activeUnprotected(context) == null',
    'UUID.nameUUIDFromBytes(',
):
    assert text in repo, text

for label in ("常用", "身体作息", "吃喝生活", "出行活动", "工作学习", "放松玩耍"):
    assert f'LifeActionCategory("{label}"' in repo, label
for label in ("吃饭", "喝水", "走路", "学习", "发呆", "上厕所", "休息", "起床", "回家", "写申论"):
    assert f'LifeActionChoice("{label}"' in repo, label

assert 'ModalBottomSheet(' in sheet
assert 'rememberModalBottomSheetState(skipPartiallyExpanded = true)' in sheet
assert 'LifeActionRecorder.start(context, choice.name)' in sheet
assert 'LifeActionRecorder.recordInstant(context, choice.name)' in sheet
assert 'LifeActionRecorder.finish(context)' in sheet
assert 'onSaved()' in sheet
assert 'LifeActionSheet(' in home and 'LifeActionSheet(' in more
assert 'R.drawable.jlz_home_portrait' in home
assert 'R.drawable.jlz_welcome_portrait' in home
assert 'QAvatarHomeControls()' in home
assert 'StatusLightHomeCard()' in home
assert 'is PresenceRoute.Today -> LifeTodayScreen()' in home
assert '"生活流水" -> it.lane == "life"' in chain
assert '"日记" -> it.lane == "moment" || it.lane == "reply"' in chain
assert 'it.type in setOf("between_status", "between_moment", "presence_plan", "life_action")' in chain

# No database migration or direct sync added; life actions are local truth until P4.
assert "private const val DB_VERSION = 1" in store
assert re.search(r'fun recordTimeline\(', store)
assert "RuntimeApiClient" not in repo and "BetweenOutbox" not in repo
print("life-action-source-contract: PASS")
