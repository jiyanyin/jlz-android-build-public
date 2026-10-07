package dev.jlz.presence.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CommandExecutionLedgerTest {
    @Test fun completedIntentSurvivesRestartAndDifferentTransportId() {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        ctx.deleteDatabase("bridge_execution.db")
        val first = RuntimeCommand("cloud-id", "notify", JSONObject(), intentId="stable-intent")
        CommandExecutionLedger(ctx).use { assertNull(it.reserve(first)); it.finish(first,Pair(true,"done")) }
        CommandExecutionLedger(ctx).use {
            assertEquals(Pair(true,"done"),it.reserve(first.copy(id="home-id")))
            assertNull(it.reserve(first.copy(deviceId="tablet")))
        }
    }
    @Test fun crashAfterReservationIsUncertainAndNeverRepeated() {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        ctx.deleteDatabase("bridge_execution.db")
        val command = RuntimeCommand("one","notify",JSONObject())
        CommandExecutionLedger(ctx).use { assertNull(it.reserve(command)) }
        CommandExecutionLedger(ctx).use {
            val prior = it.reserve(command)!!
            assertFalse(prior.first)
            assertTrue(prior.second.contains("execution_uncertain"))
        }
    }
}
