package dev.jlz.presence.focus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.jlz.presence.WebShellActivity
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.notification.NotificationReplyReceiver
import dev.jlz.presence.notification.PendingReplyStore
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class FocusGateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName.ifBlank { "这个 App" })

        setContent {
            val scope = rememberCoroutineScope()

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF111014)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(82.dp).background(Color(0xFF231F28), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("JLZ", color = Color(0xFFD4B06A))
                        }

                        Text(
                            "先不去 " + appLabel,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = 22.dp)
                        )
                        Text(
                            reason.ifBlank { "这段时间交给我。先把眼前这一段做完。" },
                            color = Color(0xFFEAE5EC),
                            modifier = Modifier.padding(top = 10.dp, bottom = 26.dp)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { finish() }) {
                                Text("好，回去")
                            }

                            Button(onClick = {
                                val requestId = UUID.randomUUID().toString()
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val app = applicationContext
                                        LocalLifeStore(app).recordTimeline(
                                            type = "focus_request",
                                            title = "想临时出来",
                                            detail = appLabel,
                                            eventId = requestId,
                                            intentId = requestId
                                        )
                                        val outbox = PendingReplyStore(app)
                                        outbox.keep(
                                            text = "我想临时打开 " + appLabel,
                                            parentEventId = requestId,
                                            intentId = requestId
                                        )
                                        val settings = RuntimeSettingsRepository(app).load()
                                        if (
                                            settings.baseUrl.isNotBlank() &&
                                            settings.token.isNotBlank()
                                        ) {
                                            val api = RuntimeApiClient(settings)
                                            runCatching { outbox.sync(api, limit = 20) }
                                        }
                                    }

                                    startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            android.net.Uri.parse(
                                                "https://between-worlds-prod.onrender.com/?shell=android#echo"
                                            ),
                                            this@FocusGateActivity,
                                            WebShellActivity::class.java
                                        )
                                            .putExtra(
                                                NotificationReplyReceiver.EXTRA_EVENT_ID,
                                                requestId
                                            )
                                            .putExtra(
                                                NotificationReplyReceiver.EXTRA_INTENT_ID,
                                                requestId
                                            )
                                            .addFlags(
                                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                                            )
                                    )
                                    finish()
                                }
                            }) {
                                Text("跟你说一声")
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "focus_package"
        private const val EXTRA_REASON = "focus_reason"

        fun show(
            context: Context,
            packageName: String,
            reason: String
        ) {
            context.startActivity(
                Intent(context, FocusGateActivity::class.java)
                    .putExtra(EXTRA_PACKAGE, packageName)
                    .putExtra(EXTRA_REASON, reason)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        }
    }
}
