package dev.jlz.presence.runtime

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
class PendingCommandReportStoreTest {
    private lateinit var context: Context
    private val backend = "https://runtime.invalid"
    private fun body(id: String = "cmd-1") = JSONObject()
        .put("command_id", id).put("device_id", "phone").put("result", "original")
    private fun ack(id: String = "cmd-1", status: String = "completed") = JSONObject()
        .put("ok", true).put("command", JSONObject().put("id", id).put("status", status))
    // SQLiteOpenHelper only implements AutoCloseable on newer Android SDKs.
    private fun withStore(block: (PendingCommandReportStore) -> Unit) {
        val store = PendingCommandReportStore(context)
        try { block(store) } finally { store.close() }
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("jlz_command_reports_v1.db")
    }
    @Test fun survivesFailureAndRestartWithoutChangingExecutionEvidence() {
        withStore { store ->
            store.enqueue(backend, body())
            store.sync(backend, "phone") { throw java.io.IOException("offline") }
        }
        withStore { store ->
            store.enqueue(backend, body().put("result", "replacement"))
            assertEquals("original", store.pending(backend, "phone").single().getString("result"))
            store.sync(backend, "phone") { ack() }
            assertTrue(store.pending(backend, "phone").isEmpty())
            store.sync(backend, "phone") { fail("acknowledged report sent again"); ack() }
        }
    }
    @Test fun backendDeviceAndAcknowledgementMustMatch() {
        withStore { store ->
            store.enqueue(backend, body())
            store.sync("https://other.invalid", "phone") { fail("cross backend send"); ack() }
            store.sync(backend, "other-phone") { fail("cross device send"); ack() }
            for (response in listOf(JSONObject().put("ok", true), ack("other"), ack(status="queued"))) {
                store.sync(backend, "phone") { response }
                assertEquals(1, store.pending(backend, "phone").size)
            }
            store.sync(backend + "/", "phone") { ack(status="failed") }
            assertTrue(store.pending(backend, "phone").isEmpty())
        }
    }
}
