package dev.jlz.presence.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.life.LifeHealthJournalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun LifeHealthJournalPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val journal = remember {
        LifeHealthJournalStore(context.applicationContext)
    }
    val timeline = remember {
        LocalLifeStore(context.applicationContext)
    }

    var hydrationMl by remember { mutableStateOf(0) }
    var periodOpen by remember { mutableStateOf(false) }
    var expectedDate by remember { mutableStateOf<String?>(null) }
    var expectedSource by remember { mutableStateOf<String?>(null) }
    var foodText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            Triple(
                journal.hydrationTotal(),
                journal.currentOpenPeriod() != null,
                journal.prediction()
            )
        }
        hydrationMl = snapshot.first
        periodOpen = snapshot.second
        expectedDate = snapshot.third?.let {
            LocalDate.ofEpochDay(
                it.expectedStartEpochDay
            ).toString()
        }
        expectedSource = snapshot.third?.source
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "生活与身体",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                if (periodOpen) {
                    "生理期 · 已记录实际开始"
                } else if (expectedDate != null) {
                    "生理期 · 预计 " + expectedDate
                } else {
                    "生理期 · 还没有足够历史做预计"
                }
            )

            if (expectedDate != null && !periodOpen) {
                Text(
                    "预计来源：" +
                        expectedSource.orEmpty() +
                        "；只作生活提醒，不作诊断或避孕依据。",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                if (!periodOpen) {
                    Button(
                        onClick = {
                            scope.launch {
                                val period =
                                    withContext(Dispatchers.IO) {
                                        journal.recordPeriodStart()
                                    }
                                timeline.recordTimeline(
                                    type = "cycle",
                                    title = "实际来潮",
                                    detail = LocalDate.ofEpochDay(
                                        period.startEpochDay
                                    ).toString()
                                )
                                note = "已按今天记录实际开始。"
                                refresh()
                            }
                        }
                    ) {
                        Text("今天开始")
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                val period =
                                    withContext(Dispatchers.IO) {
                                        journal.recordPeriodEnd()
                                    }
                                if (period != null) {
                                    timeline.recordTimeline(
                                        type = "cycle",
                                        title = "实际结束",
                                        detail = LocalDate.ofEpochDay(
                                            period.endEpochDay
                                                ?: LocalDate.now()
                                                    .toEpochDay()
                                        ).toString()
                                    )
                                }
                                note = "已按今天记录实际结束。"
                                refresh()
                            }
                        }
                    ) {
                        Text("今天结束")
                    }
                }
            }

            Text("今天喝了 " + hydrationMl + " ml")
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(4.dp)
            ) {
                listOf(150, 250, 350, 500).forEach { ml ->
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    journal.addHydration(ml)
                                    timeline.recordTimeline(
                                        type = "hydration",
                                        title = "喝水",
                                        detail =
                                            ml.toString() + " ml"
                                    )
                                }
                                refresh()
                            }
                        }
                    ) {
                        Text("+" + ml)
                    }
                }
            }

            OutlinedTextField(
                value = foodText,
                onValueChange = { foodText = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("今天吃了什么")
                }
            )

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    "午饭" to "lunch",
                    "晚饭" to "dinner",
                    "零食" to "snack",
                    "其他" to "other"
                ).forEach { (label, kind) ->
                    TextButton(
                        onClick = {
                            val text = foodText.trim()
                            if (text.isNotEmpty()) {
                                foodText = ""
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        journal.addFood(
                                            mealType = kind,
                                            text = text
                                        )
                                        timeline.recordTimeline(
                                            type = "food",
                                            title = label,
                                            detail = text
                                        )
                                    }
                                    note =
                                        "记下了，不会凭空补卡路里。"
                                }
                            }
                        }
                    ) {
                        Text(label)
                    }
                }
            }

            if (note.isNotBlank()) {
                Text(note)
            }
        }
    }
}
