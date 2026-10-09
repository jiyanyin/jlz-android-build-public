package dev.jlz.presence.callback

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.jlz.presence.R
import dev.jlz.presence.MainActivity
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.ui.components.IceButton
import dev.jlz.presence.ui.theme.*

class PresenceCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val intentId = intent.getStringExtra(EXTRA_INTENT_ID)
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "\u6211\u60f3\u627e\u4f60\u3002" }
        val topic = intent.getStringExtra(EXTRA_TOPIC).orEmpty()
        setContent {
            IceCrystalTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF090B1B)) {
                    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Image(
                            painter = painterResource(R.drawable.jlz_chat_avatar),
                            contentDescription = "纪临洲",
                            modifier = Modifier.size(88.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Text("\u7eaa\u4e34\u6d32\u6b63\u5728\u627e\u4f60", color = TextPrimary, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 22.dp))
                        if (topic.isNotBlank()) Text(topic, color = VioletGlow, modifier = Modifier.padding(top = 12.dp))
                        Text(reason, color = TextSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 28.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IceButton("\u7a0d\u540e", onClick = {
                                LocalLifeStore(applicationContext).recordTimeline(
                                    "callback", "\u7a0d\u540e\u56de\u6211", reason, eventId, intentId,
                                    metadataJson = org.json.JSONObject().put("actor", "assistant").put("status", "later").toString(),
                                    id = PresenceCallbackAdapter.stableRowId(eventId, intentId)
                                )
                                finish()
                            })
                            IceButton("\u63a5\u6211", onClick = {
                                LocalLifeStore(applicationContext).recordTimeline(
                                    "callback", "\u63a5\u4e86\u6211\u7684\u7535\u8bdd", reason, eventId, intentId,
                                    metadataJson = org.json.JSONObject().put("actor", "assistant").put("status", "accepted").toString(),
                                    id = PresenceCallbackAdapter.stableRowId(eventId, intentId)
                                )
                                startActivity(Intent(this@PresenceCallbackActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, "chat").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                                finish()
                            }, primary = true)
                        }
                    }
                }
            }
        }
    }
    companion object {
        const val EXTRA_EVENT_ID = "callback_event_id"
        const val EXTRA_INTENT_ID = "callback_intent_id"
        const val EXTRA_REASON = "callback_reason"
        const val EXTRA_TOPIC = "callback_topic"
    }
}
