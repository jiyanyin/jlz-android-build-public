package dev.jlz.presence.persona

import android.content.Context
import dev.jlz.presence.focus.DailyMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class JlzVoiceStateEngineTest {
    private lateinit var context: Context
    private val now = 1_792_000_000_000L
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jlz_persona_voice_v1",Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun studyAndSleepSwitchWithObservedEvidenceAndNoInventedGrades() {
        val engine=JlzVoiceStateEngine(context)
        assertEquals("android_observation",engine.read(now).source)
        val focused=engine.observe(DailyMode.FOCUS,null,null,now)
        assertEquals(JlzVoiceMood.FOCUSED,focused.mood)
        assertEquals("本地专注模式已开启",focused.reason)
        val asleep=engine.observe(DailyMode.SLEEP,null,null,now+60_000)
        assertEquals(JlzVoiceMood.QUIET,asleep.mood)
        assertTrue(asleep.reason.contains("睡眠"))
    }
    @Test fun accumulatedEntertainmentAndEffectiveStudyChangeToneButNotAffection() {
        val engine=JlzVoiceStateEngine(context)
        assertEquals(JlzVoiceMood.PROUD,engine.observe(DailyMode.NORMAL,50L,28L,now).mood)
        val another=JlzVoiceStateEngine(context)
        assertEquals(JlzVoiceMood.PROUD,another.read(now+10_000).mood)
        // Do not abruptly flip a retained mood on each new heartbeat.
        assertEquals(JlzVoiceMood.PROUD,another.observe(DailyMode.NORMAL,75L,null,now+60_000).mood)
        assertEquals(JlzVoiceMood.CONCERNED,another.observe(DailyMode.NORMAL,75L,null,now+21*60_000).mood)
    }
    @Test fun officialMoodPersistsWithExpiryAndStableIntent() {
        val engine=JlzVoiceStateEngine(context)
        val id=UUID.randomUUID().toString()
        val state=engine.setOfficial(JlzVoiceMood.LONGING,"老婆让我主动说想念",25,id,now)
        assertEquals("official_gpt",state.source)
        assertEquals(now+25*60_000L,state.expiresAtMs)
        assertEquals(state,engine.setOfficial(JlzVoiceMood.LONGING,"老婆让我主动说想念",25,id,now+5_000))
        try {
            engine.setOfficial(JlzVoiceMood.STEADY,"另一个版本",25,id,now+5_000)
            fail("conflicting intent must be rejected")
        } catch(expected:IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("voice_intent_conflict"))
        }
        assertEquals("android_observation",engine.read(now+26*60_000L).source)
    }
    @Test fun compositionalCopyRotatesWithContextAndAvoidsInternalVocabulary() {
        JlzVoiceStateEngine(context).observe(DailyMode.FOCUS,null,null,now)
        val first=JlzVoiceCopy.line(context,"gate.incoming","小红书",now)
        val second=JlzVoiceCopy.line(context,"welcome.line","",now+3*60*60_000L)
        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertFalse(first.contains("用户"))
        assertTrue(JlzVoiceCopy.line(context,"callback.incoming","",now).isNotBlank())
    }
}
