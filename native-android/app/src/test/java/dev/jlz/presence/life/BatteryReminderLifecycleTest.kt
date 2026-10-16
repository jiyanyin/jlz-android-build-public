package dev.jlz.presence.life

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatteryReminderLifecycleTest {
    @Test fun upgradeStartsBelowFortyThenWarnsEveryThreeActualPointsAndStopsWhileCharging() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("jlz_local_reminders_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("battery_lowest_band", 17).putInt("battery_cycle", 3).commit()
        val engine = LocalLifeReminderEngine(context)
        fun reading(percent: Int, charging: Boolean = false) {
            engine.battery(Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, percent)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_PLUGGED, if (charging) 1 else 0))
        }
        reading(40)
        assertEquals(-1, prefs.getInt("battery_lowest_notified_percent", -1))
        reading(39)
        assertEquals(39, prefs.getInt("battery_lowest_notified_percent", -1))
        reading(38); reading(37)
        assertEquals(39, prefs.getInt("battery_lowest_notified_percent", -1))
        reading(36); reading(36); reading(35)
        assertEquals(36, prefs.getInt("battery_lowest_notified_percent", -1))
        assertEquals(1, prefs.getInt("copy_count:battery36", 0))
        reading(35, charging = true)
        reading(38, charging = true)
        assertEquals(36, prefs.getInt("battery_lowest_notified_percent", -1))
        reading(38)
        assertEquals(4, prefs.getInt("battery_cycle", 0))
        assertEquals(38, prefs.getInt("battery_lowest_notified_percent", -1))
        reading(36)
        assertEquals(38, prefs.getInt("battery_lowest_notified_percent", -1))
        reading(35)
        assertEquals(35, prefs.getInt("battery_lowest_notified_percent", -1))
    }
}
