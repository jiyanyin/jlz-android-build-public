package dev.jlz.presence.life

import android.Manifest
import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CalendarBoundaryTest {
    @Test fun historicalRangeRetainedAndUnboundedRangeRejected() {
        val bridge = NativeCalendarBridge(RuntimeEnvironment.getApplication())
        val start = 1_600_000_000_000L
        val day = bridge.snapshot(start, start + 86_400_000L)
        assertEquals(start, day.getLong("range_start_ms"))
        assertEquals(start + 86_400_000L, day.getLong("range_end_ms"))
        assertEquals("calendar_invalid_range", bridge.snapshot(start, start - 1).getString("reason"))
        assertEquals("calendar_invalid_range", bridge.snapshot(start, start + 43L * 86_400_000).getString("reason"))
    }

    @Test fun cloudIdsCannotBeInferredOrLinkedWithoutConfirmation() {
        val app: Application = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val result = NativeCalendarBridge(app).add(JSONObject().put("operation", "link_verified_cloud_event")
            .put("cloud_event_id", "unverified"))
        assertFalse(result.first)
        assertEquals("calendar_link_confirmation_required", result.second)
    }
}
