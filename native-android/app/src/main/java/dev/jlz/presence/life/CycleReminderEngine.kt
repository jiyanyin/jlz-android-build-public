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
            prediction.expectedStartEpochDay - 2L

        if (today == dueSoonDay) {
            val key =
                "PERIOD_DUE_SOON:" +
                    prediction.expectedStartEpochDay

            if (journal.markEventOnce(key)) {
                val detail =
                    "预计 " +
                        LocalDate.ofEpochDay(
                            prediction.expectedStartEpochDay
                        ) +
                        " 左右开始；依据：" +
                        prediction.source

                timeline.recordTimeline(
                    type = "PERIOD_DUE_SOON",
                    title = "生理期预计快到了",
                    detail = detail
                )

                NotificationAdapter(appContext)
                    .showMessage(
                        title = "我提前提醒你一下",
                        message =
                            "生理期预计还有两天左右。只是预计，我先替你记着。"
                    )
            }
        }

        return prediction
    }
}
