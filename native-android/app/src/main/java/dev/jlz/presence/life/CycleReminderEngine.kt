package dev.jlz.presence.life

import android.content.Context
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.notification.NotificationAdapter
import java.time.LocalDate

class CycleReminderEngine(private val context: Context) {
    private val appContext = context.applicationContext
    private val journal =
        LifeHealthJournalStore(appContext)
    private val timeline =
        LocalLifeStore(appContext)

    fun evaluateToday(): CyclePrediction? {
        val prediction = journal.prediction()
            ?: return null

        val today = LocalDate.now().toEpochDay()
        val dueSoonDay =
            prediction.expectedStartEpochDay - 3L

        // At most one nudge per day near the estimated date; tolerant of a
        // missed heartbeat and never declares the period actually started.
        if (today in dueSoonDay..(prediction.expectedStartEpochDay + 1L)) {
            val key =
                "PERIOD_DUE_SOON:" +
                    prediction.expectedStartEpochDay + ":" + today

            if (!journal.isEventRecorded(key)) {
                val detail =
                    "预计 " +
                        LocalDate.ofEpochDay(
                            prediction.expectedStartEpochDay
                        ) +
                        " 左右开始；依据：" +
                        prediction.source

                val result = NotificationAdapter(appContext)
                    .showMessage(
                        title = "我提前提醒你一下",
                        message = "生理期预计就在这几天。只是预计，不代表已经开始。"
                    )
                if (result.ok && journal.markEventOnce(key)) {
                    timeline.recordTimeline(
                        type = "PERIOD_DUE_SOON",
                        title = "生理期预计快到了",
                        detail = detail
                    )
                }
            }
        }

        return prediction
    }
}
