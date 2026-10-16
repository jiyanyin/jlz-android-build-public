package dev.jlz.presence.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import dev.jlz.presence.capture.CaptureTrafficPolicy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutomaticCaptureAppDialogTest {
    private lateinit var activity: Activity
    private lateinit var policy: CaptureTrafficPolicy
    private val choices = listOf(
        AutomaticCaptureAppChoice("com.xingin.xhs", "小红书"),
        AutomaticCaptureAppChoice("tv.danmaku.bili", "哔哩哔哩"),
    )

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("jlz_capture_traffic_v1", Context.MODE_PRIVATE).edit().clear().commit()
        policy = CaptureTrafficPolicy(context)
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    private fun open(selected: Set<String> = policy.automaticSelection().second) =
        AutomaticCaptureAppDialog.show(activity, choices, selected) { policy.configureAutomatic(true, it) }
            .also { shadowOf(Looper.getMainLooper()).idle() }

    private fun toggle(dialog: AlertDialog, index: Int) {
        val list = dialog.listView
        list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index))
    }

    @Test fun visibleListAndNonemptyChoicePersistAcrossReopen() {
        val dialog = open()
        assertNotNull("Checkbox list must be attached, not replaced by message text", dialog.listView.parent)
        assertTrue(dialog.listView.isShown)
        assertEquals(2, dialog.listView.adapter.count)
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        toggle(dialog, 0)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        assertEquals(true to setOf("com.xingin.xhs"), CaptureTrafficPolicy(activity).automaticSelection())
        val reopened = open()
        assertTrue(reopened.listView.isItemChecked(0))
        assertFalse(reopened.listView.isItemChecked(1))
        reopened.dismiss()
    }

    @Test fun cancellingChangedChecksPreservesSavedSelection() {
        assertTrue(policy.configureAutomatic(true, setOf("com.xingin.xhs")))
        val dialog = open()
        toggle(dialog, 0)
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        toggle(dialog, 1)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(true to setOf("com.xingin.xhs"), CaptureTrafficPolicy(activity).automaticSelection())
    }

    @Test fun failedSaveKeepsSelectionOpenForRetry() {
        val dialog = AutomaticCaptureAppDialog.show(activity, choices, emptySet()) { false }
        shadowOf(Looper.getMainLooper()).idle()
        toggle(dialog, 1)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertTrue(dialog.listView.isItemChecked(1))
        assertEquals(false to emptySet<String>(), policy.automaticSelection())
        dialog.dismiss()
    }

    @Test fun manyApplicationsKeepNativeScrollableListAtLargeFont() {
        val configuration = activity.resources.configuration
        configuration.fontScale = 1.6f
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        val many = (1..40).map { AutomaticCaptureAppChoice("test.app$it", "可选择的应用 $it") }
        val dialog = AutomaticCaptureAppDialog.show(activity, many, emptySet()) { true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.listView.isShown)
        assertEquals(40, dialog.listView.adapter.count)
        toggle(dialog, 39)
        assertTrue(dialog.listView.isItemChecked(39))
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.dismiss()
    }
}
