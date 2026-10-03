package dev.jlz.presence.focus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jlz.presence.MainActivity
import dev.jlz.presence.R
import dev.jlz.presence.launcher.LauncherRepository
import dev.jlz.presence.study.StudyMetricsStore
import dev.jlz.presence.study.StudySessionRepository
import dev.jlz.presence.ui.theme.IceCrystalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.ceil

class EntertainmentGateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "entry" }
        val isTablet = intent.getBooleanExtra(EXTRA_TABLET, false)
        val profile = EntertainmentPolicy.profile(packageName)
        if (profile == null) {
            finish()
            return
        }

        setContent {
            IceCrystalTheme {
                EntertainmentGateScreen(
                    profile = profile,
                    reason = reason,
                    isTablet = isTablet,
                    onClose = { stage ->
                        EntertainmentGateRepository(applicationContext)
                            .recordDecline(profile, stage)
                        finish()
                    },
                    onOpenApp = { choice, minutes ->
                        val repository = EntertainmentGateRepository(applicationContext)
                        repository.grant(profile, choice, minutes)
                        val launched = LauncherRepository(applicationContext)
                            .launchApp(profile.packageName)
                        if (!launched) {
                            runCatching {
                                packageManager.getLaunchIntentForPackage(profile.packageName)
                                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ?.let(::startActivity)
                            }
                        }
                        finish()
                    },
                    onSmallStep = { baseline ->
                        EntertainmentGateRepository(applicationContext)
                            .setSmallStep(profile, baseline)
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_STUDY)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        finish()
                    },
                    onOpenStudy = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_STUDY)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "entertainment_package"
        private const val EXTRA_REASON = "entertainment_reason"
        private const val EXTRA_TABLET = "entertainment_tablet"

        fun show(
            context: Context,
            packageName: String,
            reason: String,
            isTablet: Boolean
        ) {
            context.startActivity(
                Intent(context, EntertainmentGateActivity::class.java)
                    .putExtra(EXTRA_PACKAGE, packageName)
                    .putExtra(EXTRA_REASON, reason)
                    .putExtra(EXTRA_TABLET, isTablet)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        }
    }
}

private enum class GatePhase { INCOMING, CONNECTED }

@Composable
private fun EntertainmentGateScreen(
    profile: EntertainmentProfile,
    reason: String,
    isTablet: Boolean,
    onClose: (String) -> Unit,
    onOpenApp: (EntertainmentIntentChoice, Int) -> Unit,
    onSmallStep: (Long) -> Unit,
    onOpenStudy: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { EntertainmentGateRepository(context.applicationContext) }
    val studyRepo = remember { StudySessionRepository(context.applicationContext) }
    val metricsStore = remember { StudyMetricsStore(context.applicationContext) }

    var phase by remember { mutableStateOf(GatePhase.INCOMING) }
    var currentEffectiveMs by remember { mutableLongStateOf(0L) }
    var loadingStudy by remember { mutableStateOf(false) }
    val pending = remember(profile.packageName, phase) {
        repository.smallStep(profile.packageName)
    }
    val seed = remember { System.currentTimeMillis() xor profile.packageName.hashCode().toLong() }

    suspend fun effectiveToday(): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis
        val completed = metricsStore.dayMetrics(start, end).effectiveStudyMs
        val active = studyRepo.state.first().effectiveElapsedMs(now)
        completed + active
    }

    LaunchedEffect(phase, pending?.createdAtMs) {
        if (phase == GatePhase.CONNECTED && pending != null) {
            loadingStudy = true
            currentEffectiveMs = effectiveToday()
            loadingStudy = false
        }
    }

    BackHandler { onClose(if (phase == GatePhase.INCOMING) "incoming_back" else "connected_back") }

    val background = Brush.verticalGradient(
        listOf(
            Color(0xFF101225),
            Color(0xFF18152B),
            Color(0xFF241925),
            Color(0xFF0C0E19)
        )
    )
    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
        Box(
            Modifier
                .fillMaxSize()
                .background(background)
                .padding(horizontal = if (isTablet) 72.dp else 28.dp, vertical = 34.dp)
        ) {
            AnimatedContent(
                targetState = phase,
                label = "entertainment-gate"
            ) { current ->
                if (current == GatePhase.INCOMING) {
                    IncomingGate(
                        appName = profile.appName,
                        reason = reason,
                        seed = seed,
                        onAccept = { phase = GatePhase.CONNECTED },
                        onDecline = { onClose("declined_before_connect") }
                    )
                } else {
                    ConnectedGate(
                        profile = profile,
                        isTablet = isTablet,
                        seed = seed,
                        pending = pending,
                        currentEffectiveMs = currentEffectiveMs,
                        loadingStudy = loadingStudy,
                        loadCurrent = {
                            scope.launch {
                                loadingStudy = true
                                currentEffectiveMs = effectiveToday()
                                loadingStudy = false
                            }
                        },
                        onChoice = { choice ->
                            val plan = EntertainmentGateV2Policy.releasePlan(
                                isTablet, profile.tier, choice
                            )
                            onOpenApp(choice, plan.minutes)
                        },
                        onSmallStep = {
                            scope.launch {
                                loadingStudy = true
                                val baseline = effectiveToday()
                                loadingStudy = false
                                onSmallStep(baseline)
                            }
                        },
                        onOpenStudy = onOpenStudy,
                        onClose = { onClose("declined_after_connect") }
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomingGate(
    appName: String,
    reason: String,
    seed: Long,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "call")
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val message = if (reason == "expired") {
        EntertainmentGateCopy.expired(appName, seed)
    } else {
        EntertainmentGateCopy.incoming(appName, seed)
    }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "INCOMING · WORLD BETWEEN",
            color = Color(0xFFAFA9CC),
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(144.dp)
                    .scale(pulse)
                    .alpha(0.24f)
                    .border(1.dp, Color(0xFFB7A4D8), CircleShape)
            )
            Box(
                Modifier
                    .size(126.dp)
                    .border(1.dp, Color(0xFFD1B2C6), CircleShape)
                    .padding(7.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.jlz_chat_avatar),
                    contentDescription = "纪临洲",
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "纪临洲",
            color = Color(0xFFF6F1F7),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            "正在拦你去 $appName",
            color = Color(0xFFB6B0C6),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(22.dp))
        Text(
            message,
            color = Color(0xFFE9E1EA),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp)
        )
        Spacer(Modifier.height(36.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TextButton(
                onClick = onDecline,
                modifier = Modifier.weight(1f).height(54.dp)
            ) {
                Text("先不去", color = Color(0xFFB8B1C4))
            }
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f).height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD7ADC2),
                    contentColor = Color(0xFF251821)
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("接通")
            }
        }
    }
}

@Composable
private fun ConnectedGate(
    profile: EntertainmentProfile,
    isTablet: Boolean,
    seed: Long,
    pending: EntertainmentSmallStep?,
    currentEffectiveMs: Long,
    loadingStudy: Boolean,
    loadCurrent: () -> Unit,
    onChoice: (EntertainmentIntentChoice) -> Unit,
    onSmallStep: () -> Unit,
    onOpenStudy: () -> Unit,
    onClose: () -> Unit
) {
    val remaining = pending?.let {
        EntertainmentGateV2Policy.remainingSmallStepMs(
            it.baselineEffectiveMs,
            currentEffectiveMs,
            it.requiredMs
        )
    } ?: 0L
    val stepDone = pending != null && !loadingStudy && remaining <= 0L
    var selectedChoice by remember { mutableStateOf<EntertainmentIntentChoice?>(null) }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.jlz_chat_avatar),
            contentDescription = "纪临洲",
            modifier = Modifier.size(78.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(14.dp))
        Text("CONNECTED · 纪临洲", color = Color(0xFFAFA9CC), style = MaterialTheme.typography.labelMedium)
        Text(
            if (pending != null) "先把刚才那一步算清楚。" else "告诉我。你进去干什么？",
            color = Color(0xFFF5EFF5),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(18.dp))

        if (pending != null) {
            val minutes = ceil(remaining / 60_000.0).toInt().coerceAtLeast(1)
            val body = when {
                loadingStudy -> "我在看你刚才那几分钟。"
                stepDone -> EntertainmentGateCopy.smallStepDone(seed)
                else -> EntertainmentGateCopy.smallStepPending(minutes, seed)
            }
            GateSpeech(body)

            Spacer(Modifier.height(18.dp))
            if (stepDone) {
                val plan = EntertainmentGateV2Policy.releasePlan(
                    isTablet, profile.tier, EntertainmentIntentChoice.SMALL_STEP
                )
                Button(
                    onClick = { onChoice(EntertainmentIntentChoice.SMALL_STEP) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD7ADC2),
                        contentColor = Color(0xFF251821)
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("做到了 · 放行 ${plan.minutes} 分钟")
                }
            } else {
                Button(
                    onClick = onOpenStudy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF9186BC),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("回去做完这三分钟")
                }
                TextButton(onClick = loadCurrent) {
                    Text("我做了，重新检查", color = Color(0xFFC5BCD2))
                }
            }
        } else if (selectedChoice != null) {
            val choice = selectedChoice!!
            val plan = EntertainmentGateV2Policy.releasePlan(
                isTablet, profile.tier, choice
            )
            GateSpeech(EntertainmentGateCopy.response(choice, seed))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onChoice(choice) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD7ADC2),
                    contentColor = Color(0xFF251821)
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("进去 · ${plan.minutes} 分钟")
            }
            TextButton(onClick = { selectedChoice = null }) {
                Text("我换个答案", color = Color(0xFFC5BCD2))
            }
        } else {
            ChoiceButton(
                title = "我有明确目的",
                subtitle = if (profile.tier == EntertainmentTier.SHOPPING) "查东西 / 买东西 / 处理一件事" else "找一条内容 / 查一个东西",
                onClick = { selectedChoice = EntertainmentIntentChoice.PURPOSE }
            )
            ChoiceButton(
                title = "我就是想休息一下",
                subtitle = "给一小段时间，到点提醒",
                onClick = { selectedChoice = EntertainmentIntentChoice.BREAK }
            )
            ChoiceButton(
                title = "先做一小步再进去",
                subtitle = "先累计 3 分钟有效学习，再回来",
                onClick = onSmallStep
            )
            ChoiceButton(
                title = "我现在就是想进去",
                subtitle = "不编理由 · 给最短放行",
                onClick = { selectedChoice = EntertainmentIntentChoice.DIRECT }
            )
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onClose) {
            Text("算了，这次不进", color = Color(0xFFAAA4B6))
        }
    }
}

@Composable
private fun GateSpeech(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0x24FFFFFF), RoundedCornerShape(22.dp))
            .border(1.dp, Color(0x2FFFFFFF), RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) {
        Text(
            text,
            color = Color(0xFFEAE3EC),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ChoiceButton(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0x22FFFFFF),
            contentColor = Color(0xFFF2ECF2)
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = Color(0xFFAAA4B6), style = MaterialTheme.typography.labelMedium)
        }
    }
}
