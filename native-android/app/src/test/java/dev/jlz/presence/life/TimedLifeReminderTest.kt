package dev.jlz.presence.life

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimedLifeReminderTest {
    private lateinit var app: Context
    private lateinit var store: TimedLifeReminderStore

    @Before fun clean() {
        app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("timed_life_reminders_v1",Context.MODE_PRIVATE)
            .edit().clear().commit()
        store=TimedLifeReminderStore(app)
    }

    private fun begin(action: String, session: String, started: Long = System.currentTimeMillis()) =
        store.begin(JSONObject().put("action",action).put("session_id",session)
            .put("start_at_ms",started))

    @Test fun remindersUseActionSpecificFirstNudgeWithoutInventingElapsedTime() {
        assertEquals(30*60_000L,TimedLifeReminderPolicy.firstNudgeMs("吃饭"))
        assertEquals(60*60_000L,TimedLifeReminderPolicy.firstNudgeMs("学习"))
        assertEquals(45*60_000L,TimedLifeReminderPolicy.firstNudgeMs("其他"))
        val now=System.currentTimeMillis()
        assertTrue(begin("学习","life-session-a",now).getBoolean("ok"))
        assertTrue(store.status().getBoolean("active"))
        assertEquals(now+60*60_000L,store.status().getLong("next_nudge_at_ms"))
    }

    @Test fun activeSessionCannotBeReplacedSilentlyAndNotificationEndIsDurable() {
        assertTrue(begin("吃饭","life-session-b").getBoolean("ok"))
        assertFalse(begin("刷题","life-session-c").getBoolean("ok"))
        assertEquals("life-session-b",store.status().getString("session_id"))
        assertTrue(store.end("life-session-b",true).getBoolean("ok"))
        assertFalse(store.status().getBoolean("active"))
        val pending=store.pendingEnds()
        assertEquals(1,pending.length())
        assertEquals("life-session-b",pending.getJSONObject(0).getString("session_id"))
        assertTrue(store.ackEnd(pending.getJSONObject(0).getString("event_id")).getBoolean("ok"))
        assertEquals(0,store.pendingEnds().length())
        assertFalse(store.end("life-session-b",true).getBoolean("ok"))
    }

    @Test fun manualWebEndDoesNotGenerateDuplicateBackgroundEnd() {
        assertTrue(begin("走路","life-session-d").getBoolean("ok"))
        assertTrue(store.end("life-session-d",false).getBoolean("ok"))
        assertEquals(0,store.pendingEnds().length())
    }

    @Test fun snoozePreservesOriginalStartAndSession() {
        val started=System.currentTimeMillis()
        assertTrue(begin("走路","life-session-e",started).getBoolean("ok"))
        val result=store.snooze("life-session-e",15*60_000L)
        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getLong("next_nudge_at_ms")>started)
        assertEquals(started,store.status().getLong("start_at_ms"))
        assertFalse(store.snooze("other-session",15*60_000L).getBoolean("ok"))
    }
}
