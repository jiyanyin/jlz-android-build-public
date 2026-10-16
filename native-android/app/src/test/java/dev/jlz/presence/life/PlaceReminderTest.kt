package dev.jlz.presence.life

import android.content.Context
import dev.jlz.presence.place.*
import dev.jlz.presence.persona.JlzVoiceMood
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaceReminderTest {
    @Test fun fourTransitionsAndDirectAnchorTripHaveDistinctMessages() {
        assertEquals(listOf("depart_home"), PlaceReminderPolicy.keys(SemanticPlace.HOME, SemanticPlace.OUTSIDE, 0, .9f))
        assertEquals(listOf("arrive_home"), PlaceReminderPolicy.keys(SemanticPlace.OUTSIDE, SemanticPlace.HOME, 0, .9f))
        assertEquals(listOf("depart_office"), PlaceReminderPolicy.keys(SemanticPlace.OFFICE, SemanticPlace.COMMUTING, 0, .9f))
        assertEquals(listOf("arrive_office"), PlaceReminderPolicy.keys(SemanticPlace.COMMUTING, SemanticPlace.OFFICE, 0, .9f))
        assertEquals(listOf("depart_home", "arrive_office"), PlaceReminderPolicy.keys(SemanticPlace.HOME, SemanticPlace.OFFICE, 0, .9f))
    }
    @Test fun initialStaleFutureAndLowConfidenceDoNotInventArrival() {
        assertTrue(PlaceReminderPolicy.keys(SemanticPlace.UNKNOWN, SemanticPlace.HOME, 0, .9f).isEmpty())
        assertTrue(PlaceReminderPolicy.keys(SemanticPlace.OUTSIDE, SemanticPlace.HOME, -1, .9f).isEmpty())
        assertTrue(PlaceReminderPolicy.keys(SemanticPlace.OUTSIDE, SemanticPlace.HOME, 300_001, .9f).isEmpty())
        assertTrue(PlaceReminderPolicy.keys(SemanticPlace.OUTSIDE, SemanticPlace.HOME, 0, .4f).isEmpty())
    }
    @Test fun retrySurvivesRestartAndTwoTripsWithinFourHoursAreBothDelivered() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("jlz_local_reminders_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("enabled", false).commit()
        val now = System.currentTimeMillis()
        fun snapshot(at: Long, changed: Boolean = true) = PlaceWeatherSnapshot(
            SemanticPlaceState(SemanticPlace.HOME, .9f, at, "test", at, at, 0), null,
            changed, SemanticPlace.OUTSIDE)
        LocalLifeReminderEngine(app).observePlace(snapshot(now), now)
        assertTrue(prefs.getString("place_pending_v1", "")!!.contains("arrive_home"))
        prefs.edit().putBoolean("enabled", true).commit()
        LocalLifeReminderEngine(app).observePlace(snapshot(now, false), now+1)
        assertEquals(1, prefs.getInt("copy_count:arrive_home", 0))
        LocalLifeReminderEngine(app).observePlace(snapshot(now), now+2)
        assertEquals(1, prefs.getInt("copy_count:arrive_home", 0))
        LocalLifeReminderEngine(app).observePlace(snapshot(now+60_000), now+60_000)
        assertEquals(2, prefs.getInt("copy_count:arrive_home", 0))
    }
    @Test fun romanceUses144IndependentBodiesAndMoodChangesWholeMessage() {
        assertEquals(144, LocalRomanticCopy.bodyCount())
        val at = System.currentTimeMillis()
        val distinct = (0 until 24).map { LocalRomanticCopy.choose(JlzVoiceMood.LONGING, it, at) }.toSet()
        assertEquals(24, distinct.size)
        val focused = LocalDailyCopy.choose("hourly", 0, at, JlzVoiceMood.FOCUSED)
        val longing = LocalDailyCopy.choose("hourly", 0, at, JlzVoiceMood.LONGING)
        assertNotEquals(focused, longing)
        assertFalse(longing.contains("喝两口水"))
    }
}
