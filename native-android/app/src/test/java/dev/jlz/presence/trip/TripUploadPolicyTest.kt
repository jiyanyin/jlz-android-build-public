package dev.jlz.presence.trip

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TripUploadPolicyTest {
    @Test fun exactAcknowledgementIsRequiredBeforeDiscardingPendingCoordinates() {
        val expected = listOf("p-trip-0", "p-trip-1")
        assertTrue(TripUploadPolicy.accepted(
            JSONObject().put("ok", true).put("point_ids", JSONArray(expected)), expected))
        assertFalse(TripUploadPolicy.accepted(JSONObject().put("ok", true)
            .put("point_ids", JSONArray(listOf("other-0", "other-1"))), expected))
        assertFalse(TripUploadPolicy.accepted(JSONObject().put("ok", true)
            .put("point_ids", JSONArray(listOf("p-trip-1", "p-trip-0"))), expected))
        assertFalse(TripUploadPolicy.accepted(JSONObject().put("ok", true)
            .put("point_ids", JSONArray(listOf("p-trip-0"))), expected))
        assertFalse(TripUploadPolicy.accepted(JSONObject().put("ok", false)
            .put("point_ids", JSONArray(expected)), expected))
        assertFalse(TripUploadPolicy.accepted(JSONObject().put("ok", true), expected))
    }

    @Test fun emptyHeartbeatAlsoRequiresAnExplicitEmptyPointReceipt() {
        assertTrue(TripUploadPolicy.accepted(
            JSONObject().put("ok", true).put("point_ids", JSONArray()), emptyList()))
        assertFalse(TripUploadPolicy.accepted(
            JSONObject().put("ok", true), emptyList()))
    }

    @Test fun preciseSharingExpiresByAbsoluteConsentTimeNotNetworkHealth() {
        val started = 1_000_000L
        assertFalse(TripUploadPolicy.dueToExpire(started + 40_000L, started))
        assertFalse(TripUploadPolicy.dueToExpire(
            started + TripUploadPolicy.MAX_SESSION_MS - 1, started))
        assertTrue(TripUploadPolicy.dueToExpire(
            started + TripUploadPolicy.MAX_SESSION_MS, started))
        assertFalse(TripUploadPolicy.dueToExpire(Long.MAX_VALUE, 0L))
        assertEquals(2000, TripUploadPolicy.POINT_LIMIT)
    }
}
