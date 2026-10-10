package dev.jlz.presence.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.jlz.presence.R
import dev.jlz.presence.capture.CaptureEventStore
import dev.jlz.presence.capture.PendingScreenshotQueue
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.notification.NotificationIdentityMigration
import dev.jlz.presence.focus.FocusRepository
import dev.jlz.presence.focus.FocusState
import dev.jlz.presence.runtime.PresenceDevicePreferencesRepository
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import dev.jlz.presence.screen.AccessibilityScreenshotCaptureAdapter
import dev.jlz.presence.screen.ScreenObservationBus
import dev.jlz.presence.screen.ScreenshotCaptureResult
import dev.jlz.presence.study.StudySessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

enum class FloatingPresenceMode {
    LIFE, STUDY, FOCUS
}

/**
 * One system overlay + one foreground service shared by LIFE/STUDY/FOCUS.
 * The Q-avatar and three user-approved actions are the same in every mode.
 * This service does not call a model and does not claim screenshots can
 * already be pulled by the official GPT conversation.
 */
class FloatingPresenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val journal by lazy { CaptureEventStore(applicationContext) }
    private val lifeStore by lazy { LocalLifeStore(applicationContext) }
    private val classifier by lazy { dev.jlz.presence.focus.LocalAppClassifier(applicationContext) }
    private val study by lazy { StudySessionRepository(applicationContext) }

    private var windowManager: WindowManager? = null
    private var panel: LinearLayout? = null
    private var avatar: ChibiPresenceView? = null
    private var menu: LinearLayout? = null
    private var noteForm: LinearLayout? = null
    private var noteInput: EditText? = null
    private var status: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var mode: FloatingPresenceMode = FloatingPresenceMode.LIFE
    private var staticMessage = "JLZ"
    private var expanded = false
    private var noteOpen = false
    private var noteSourcePackage: String? = null
    private var working = false
    private var transientUntilMs = 0L

    @Volatile private var focusState: FocusState = FocusState()
    private var studyPaused = false
    private val behavior = QAvatarStateMachine()
    private var gateUntil = 0L

    override fun onCreate() {
        super.onCreate()
        liveService = this
        NotificationIdentityMigration.ensureFresh(applicationContext)
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_world_between_v3)
                .setContentTitle("我在屏幕边上")
                .setContentText("点小小的纪临洲，展开三个入口")
                .setOngoing(true)
                .build()
        )

        if (Settings.canDrawOverlays(this)) attachBubble()
        scope.launch {
            FocusRepository(applicationContext).state.collectLatest {
                focusState = it
            }
        }
        // The persisted study session is authoritative regardless of whether
        // the study screen, notification or Runtime command ends the session.
        scope.launch {
            study.state.collectLatest { session ->
                studyPaused = session.paused
                if (!session.active && mode == FloatingPresenceMode.STUDY) {
                    leaveStudyMode()
                    PresenceDevicePreferencesRepository(applicationContext).setOverlayState(
                        true, FloatingPresenceMode.LIFE.name, "给你看"
                    )
                } else if (mode == FloatingPresenceMode.STUDY) {
                    renderStatus()
                }
            }
        }
        scope.launch {
            while (isActive) {
                renderStatus()
                updateBehavior()
                delay(1_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = intent?.getStringExtra(EXTRA_MODE)
            ?.let { runCatching { FloatingPresenceMode.valueOf(it) }.getOrNull() }
            ?: mode
        intent?.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }?.let {
            staticMessage = it
        }
        if (Settings.canDrawOverlays(this)) {
            if (panel == null) attachBubble()
            avatar?.setMood(idleMood())
            renderStatus()
            if (intent?.getBooleanExtra(EXTRA_ATTENTION_NUDGE, false) == true) {
                showTransient(staticMessage, 8_000L)
            }
        }
        scope.launch {
            // Restored STUDY must not revive an already-ended session.
            val session = study.state.first()
            if (mode == FloatingPresenceMode.STUDY && !session.active) leaveStudyMode()
            PresenceDevicePreferencesRepository(applicationContext)
                .setOverlayState(enabled = true, mode = mode.name, message = staticMessage)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (liveService === this) liveService = null
        scope.cancel()
        panel?.let { runCatching { windowManager?.removeView(it) } }
        panel = null
        avatar = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
        }

    private fun leaveStudyMode() {
        if (mode != FloatingPresenceMode.STUDY) return
        mode = FloatingPresenceMode.LIFE
        staticMessage = "给你看"
        studyPaused = false
        transientUntilMs = 0L
        avatar?.setMood(idleMood())
        renderStatus()
        requestLayout()
    }

    private fun idleMood(): String = when (mode) {
        FloatingPresenceMode.STUDY, FloatingPresenceMode.FOCUS -> "watch"
        FloatingPresenceMode.LIFE -> "idle"
    }

    private fun attachBubble() {
        if (panel != null) return
        windowManager = getSystemService(WindowManager::class.java)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val character = ChibiPresenceView(this).apply {
            setMood(listOf("idle", "watch", "shy")[Random.nextInt(3)])
            contentDescription = "纪临洲，点一下展开操作"
        }
        root.addView(character)

        val hint = TextView(this).apply {
            setTextColor(Color.rgb(66, 53, 85))
            textSize = 11f
            setPadding(dp(9), dp(3), dp(9), dp(4))
            background = rounded(0xFFF4EEF9.toInt(), 12)
            visibility = View.GONE
        }
        root.addView(hint)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF7F8F3FC.toInt(), 19)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            visibility = View.GONE
        }
        fun action(label: String, click: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                textSize = 12f
                includeFontPadding = true
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(0xFF68529A.toInt(), 15)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                minHeight = dp(48)
                minWidth = dp(155)
                setOnClickListener { click() }
            }
            val item = LinearLayout.LayoutParams(
                dp(174), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(7)
            }
            actions.addView(button, item)
        }
        action("专注模式") { closeMenu(); dev.jlz.presence.focus.DailyModeActivity.open(this) }
        action("视频通话") { closeMenu(); startActivity(Intent(this, dev.jlz.presence.cowatch.CoWatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        action("睡眠模式") { closeMenu(); scope.launch { FocusRepository(applicationContext).setDailyMode(dev.jlz.presence.focus.DailyMode.SLEEP); dev.jlz.presence.focus.DailyModeActivity.open(this@FloatingPresenceService) } }
        root.addView(actions)

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFF8F4FC.toInt(), 18)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        val label = TextView(this).apply {
            text = "说点什么 · 记住当时场景"
            textSize = 11f
            setTextColor(0xFF3D3150.toInt())
        }
        form.addView(label)

        val editor = EditText(this).apply {
            setHint("随手记一句，不用选分类")
            minLines = 2
            maxLines = 4
            textSize = 12f
            setTextColor(0xFF342743.toInt())
            setHintTextColor(0xFF9587A2.toInt())
            setSingleLine(false)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        editor.minHeight = dp(84)
        form.addView(editor, LinearLayout.LayoutParams(
            dp(180), LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val noteActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun noteButton(label: String, action: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11f
                minHeight = dp(46)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setTextColor(Color.WHITE)
                background = rounded(0xFF68529A.toInt(), 12)
                setOnClickListener { action() }
            }
            noteActions.addView(
                button,
                LinearLayout.LayoutParams(dp(82), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(5)
                }
            )
        }
        noteButton("取消") { closeNote() }
        noteButton("记下来") {
            if (!working) {
                val text = editor.text.toString().trim()
                if (text.isBlank()) {
                    showTransient("先写一句嘛")
                } else if (text.length > 1200) {
                    showTransient("这条太长啦 · 先写 1200 字以内")
                } else {
                    working = true
                    val packageName = noteSourcePackage
                    val contextMode = mode.name
                    closeNote()
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val session = study.state.first()
                                val sessionId = session.sessionId.takeIf {
                                    session.active && it.isNotBlank()
                                }
                                val event = journal.add(
                                    kind = "note",
                                    text = text,
                                    originPackage = packageName,
                                    studySessionId = sessionId,
                                    mode = contextMode
                                )
                                lifeStore.recordTimeline(
                                    type = "capture_note",
                                    title = "我记下了你说的话",
                                    detail = text,
                                    eventId = event.id,
                                    metadataJson = JSONObject()
                                        .put("origin_package", packageName)
                                                .put("delivery", "local_only")
                                        .toString()
                                )
                                event
                            }
                            editor.text.clear()
                            showTransient(if (result.id.isNotBlank()) "本机已记下 · 联网后交给我" else "记录失败")
                        } catch (_: Exception) {
                            showTransient("未存成功，请再试一次")
                        } finally {
                            working = false
                        }
                    }
                }
            }
        }
        form.addView(noteActions)
        root.addView(form)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(6)
            y = dp(68)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        character.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val horizontalDelta = (event.rawX - downX).toInt()
                    val atLeft = lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT
                    lp.x = (startX + if (atLeft) horizontalDelta else -horizontalDelta)
                        .coerceIn(0, (resources.displayMetrics.widthPixels - root.width).coerceAtLeast(0))
                    lp.y = (startY + (event.rawY - downY)).toInt()
                        .coerceIn(dp(38), (resources.displayMetrics.heightPixels - root.height - dp(35)).coerceAtLeast(dp(38)))
                    runCatching { windowManager?.updateViewLayout(root, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - downX) < dp(12) &&
                        abs(event.rawY - downY) < dp(12)
                    ) {
                        character.react("surprised", idleMood())
                        if (noteOpen) closeNote() else toggleMenu()
                    } else {
                        // Snap the small avatar to the nearest screen edge.
                        // Expanded controls grow toward the screen interior.
                        val dockLeft = event.rawX < resources.displayMetrics.widthPixels / 2f
                        lp.gravity = Gravity.TOP or (if (dockLeft) Gravity.LEFT else Gravity.RIGHT)
                        root.gravity = if (dockLeft) Gravity.START else Gravity.END
                        lp.x = dp(6)
                        requestLayout()
                    }
                    true
                }
                else -> false
            }
        }

        panel = root
        avatar = character
        menu = actions
        noteForm = form
        noteInput = editor
        status = hint
        params = lp
        windowManager?.addView(root, lp)
    }

    private fun toggleMenu() {
        if (working) return
        expanded = !expanded
        menu?.visibility = if (expanded) View.VISIBLE else View.GONE
        renderStatus()
        requestLayout()
    }

    private fun closeMenu() {
        expanded = false
        menu?.visibility = View.GONE
        requestLayout()
    }

    private fun openNote() {
        // Snapshot the target before the input method/our overlay takes focus.
        noteSourcePackage = currentObservedPackage()
        closeMenu()
        noteOpen = true
        noteForm?.visibility = View.VISIBLE
        params?.let { lp ->
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            lp.y = dp(55)
        }
        requestLayout()
        noteInput?.requestFocus()
        noteInput?.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(noteInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeNote() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(noteInput?.windowToken, 0)
        noteForm?.visibility = View.GONE
        noteOpen = false
        params?.let { lp ->
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            lp.y = dp(68)
        }
        requestLayout()
    }

    private fun requestLayout() {
        val view = panel ?: return
        val lp = params ?: return
        // After expanded content is laid out, keep it within the visible
        // vertical area. This is edge docking, not third-party text detection.
        view.post {
            lp.x = dp(6)
            val maxY = (resources.displayMetrics.heightPixels - view.height - dp(35))
                .coerceAtLeast(dp(38))
            lp.y = lp.y.coerceIn(dp(38), maxY)
            runCatching { windowManager?.updateViewLayout(view, lp) }
        }
    }

    private fun renderStatus() {
        val hint = status ?: return
        if (System.currentTimeMillis() < transientUntilMs) return
        hint.text = when {
            mode == FloatingPresenceMode.FOCUS && focusState.isActiveNow() -> {
                val remain = focusState.remainingMs().coerceAtLeast(0L) / 1_000L
                "专注 · %02d:%02d".format(remain / 60L, remain % 60L)
            }
            mode == FloatingPresenceMode.STUDY -> if (studyPaused) "学习暂停中" else "我在看你学习"
            expanded -> "点我收起"
            else -> ""
        }
        hint.visibility = if (hint.text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun updateBehavior() {
        val pkg = dev.jlz.presence.screen.AccessibilityActionGateway.currentPackage().orEmpty()
        val protected = pkg.contains("permissioncontroller") || pkg.contains("incall") || pkg.contains("inputmethod") || pkg == "com.android.systemui"
        if (!captureHidden) panel?.visibility = if (protected) View.INVISIBLE else View.VISIBLE
        val modeNow = focusState.modeNow()
        val category = if (pkg.isBlank()) dev.jlz.presence.focus.LocalAppCategory.UNKNOWN else classifier.classify(pkg)
        val hour = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).hour
        val awakeEntertainment = !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked && dev.jlz.presence.screen.ScreenObservationBus.isAvailable() &&
            category in setOf(dev.jlz.presence.focus.LocalAppCategory.GAME, dev.jlz.presence.focus.LocalAppCategory.FEED) && (hour >= 23 || hour < 6)
        val sync = dev.jlz.presence.runtime.BridgeStore(applicationContext).diagnostics().optLong("last_sync")
        val offline = sync > 0L && System.currentTimeMillis() - sync > 120_000L
        val state = behavior.resolve(protected, modeNow == dev.jlz.presence.focus.DailyMode.SLEEP,
            dev.jlz.presence.cowatch.CoWatchState.active,
            modeNow == dev.jlz.presence.focus.DailyMode.FOCUS || mode == FloatingPresenceMode.STUDY,
            System.currentTimeMillis() < gateUntil, awakeEntertainment,
            modeNow == dev.jlz.presence.focus.DailyMode.BREAK, offline)
        val mood = when(state) {
            QAvatarState.SLEEP -> "sleep"
            QAvatarState.WATCHING -> "watching"
            QAvatarState.STUDY -> "watch"
            QAvatarState.ANNOYED -> "gate"
            QAvatarState.NIGHT -> "night"
            QAvatarState.BREAK -> "break"
            QAvatarState.OFFLINE -> "offline"
            else -> "idle"
        }
        avatar?.setMood(mood)
    }

    private fun showTransient(message: String, durationMs: Long = 2_800L) {
        status?.text = message
        status?.visibility = View.VISIBLE
        transientUntilMs = System.currentTimeMillis() + durationMs.coerceIn(1_500L, 15_000L)
    }

    private fun currentObservedPackage(): String? {
        val current = ScreenObservationBus.observations.value ?: return null
        if (System.currentTimeMillis() - current.observedAtMs > 30_000L) return null
        return current.packageName?.takeIf {
            it.isNotBlank() && it != packageName
        }
    }

    private suspend fun recordDistraction() {
        working = true
        val packageName = currentObservedPackage()
        val contextMode = mode.name
        try {
            withContext(Dispatchers.IO) {
                val session = study.state.first()
                val sessionId = session.sessionId.takeIf { session.active && it.isNotBlank() }
                val event = journal.add(
                    kind = "distraction",
                    originPackage = packageName,
                    studySessionId = sessionId,
                    mode = contextMode
                )
                lifeStore.recordTimeline(
                    type = "capture_distraction",
                    title = "我在摸鱼",
                    detail = packageName.orEmpty(),
                    eventId = event.id,
                    metadataJson = JSONObject()
                        .put("origin_package", packageName)
                        .put("session_id", sessionId)
                        .put("self_reported", true)
                        .put("delivery", "local_only")
                        .toString()
                )
            }
            showTransient("记下了 · 不批评你")
        } catch (_: Exception) {
            showTransient("没记下来，再点一次")
        } finally {
            working = false
        }
    }

    private suspend fun captureWithoutOverlay() {
        val view = panel ?: return
        if (working) return
        working = true
        val packageName = currentObservedPackage()
        val eventId = UUID.randomUUID().toString()
        closeMenu()

        // Hide the ENTIRE overlay, not just the avatar; never photograph
        // our buttons or the note editor as a substitute for the target app.
        val capture = try {
            view.visibility = View.INVISIBLE
            delay(320L)
            AccessibilityScreenshotCaptureAdapter().capture()
        } catch (failure: Exception) {
            ScreenshotCaptureResult.Unavailable(failure.javaClass.simpleName)
        } finally {
            view.visibility = View.VISIBLE
        }

        try {
            val sessionId: String? = null
            when (capture) {
                is ScreenshotCaptureResult.Unavailable -> {
                    withContext(Dispatchers.IO) {
                        journal.add(
                            kind = "screenshot",
                            originPackage = packageName,
                            studySessionId = sessionId,
                            mode = "MANUAL",
                            deliveryStatus = "upload_failed",
                            detail = capture.reason,
                            id = eventId
                        )
                    }
                    showTransient("截图失败 · 查看权限")
                }
                is ScreenshotCaptureResult.Captured -> {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            PendingScreenshotQueue(applicationContext).enqueue(
                                capture = capture,
                                eventId = eventId,
                                sourcePackage = packageName,
                                studySessionId = sessionId,
                                origin = "manual_q"
                            )
                        }
                    }
                    if (saved.isFailure) {
                        withContext(Dispatchers.IO) {
                            journal.add(
                                kind = "screenshot",
                                id = eventId,
                                originPackage = packageName,
                                studySessionId = sessionId,
                                mode = "MANUAL",
                                deliveryStatus = "upload_failed",
                                detail = saved.exceptionOrNull()?.message.orEmpty()
                            )
                        }
                        showTransient("本机截图暂存失败 · 请查看今日")
                        return
                    }
                    withContext(Dispatchers.IO) {
                        lifeStore.recordTimeline(
                            type = "manual_screenshot",
                            title = "让我看看",
                            detail = packageName.orEmpty(),
                            eventId = eventId,
                            intentId = eventId,
                            metadataJson = JSONObject()
                                .put("package_name", packageName)
                                .put("session_id", sessionId)
                                .put("capture_origin", "manual_q")
                                .put("delivery", "local_outbox")
                                .toString()
                        )
                    }
                    val settings = RuntimeSettingsRepository(applicationContext).load()
                    if (settings.baseUrl.isBlank() || settings.token.isBlank()) {
                        showTransient("截图已存本机 · 联网后交给我")
                        return
                    }
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            PendingScreenshotQueue(applicationContext)
                                .sendPending(
                                    api = RuntimeApiClient(settings),
                                    limit = 1,
                                    priorityEventId = eventId
                                )
                        }
                    }
                    val mine = result.getOrNull()?.find { it.eventId == eventId }
                    showTransient(
                        if (mine?.sent == true) "图片已上传 · 等我看"
                        else "截图已存本机 · 等待补送"
                    )
                }
            }
        } catch (_: Exception) {
            showTransient("截图未完成 · 请查看今日")
        } finally {
            working = false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "悬浮在场", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        @Volatile private var captureHidden = false
        @Volatile private var liveService: FloatingPresenceService? = null

        fun gateReaction() { liveService?.let { it.gateUntil = System.currentTimeMillis() + 5000; it.avatar?.setMood("gate") } }

        suspend fun <T> withoutOverlay(block: suspend () -> T): T {
            withContext(Dispatchers.Main) { captureHidden = true; liveService?.panel?.visibility = View.INVISIBLE }
            return try { delay(350); block() } finally { withContext(Dispatchers.Main) { captureHidden = false; liveService?.panel?.visibility = View.VISIBLE } }
        }

        /** The official GPT captures the underlying app, not its own Q menu. */
        suspend fun captureForRuntime(automatic: Boolean = false): ScreenshotCaptureResult {
            val view = withContext(Dispatchers.Main.immediate) {
                liveService?.panel?.also { it.visibility = View.INVISIBLE }
            }
            return try {
                if (view != null) delay(350L)
                var result: ScreenshotCaptureResult = ScreenshotCaptureResult.Unavailable("capture_not_started")
                for (attempt in 0..2) {
                    result = dev.jlz.presence.screen.AccessibilityScreenshotGateway.capture(automatic)
                    if (result is ScreenshotCaptureResult.Captured) break
                    if (attempt < 2) delay(300L * (attempt + 1))
                }
                result
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    if (view != null) view.visibility = View.VISIBLE
                }
            }
        }

        /**
         * Local attention nudge used by the app-switch capture policy.
         * It never opens ChatGPT or blocks the app; it only speaks through the
         * already-running Q overlay after the configured long-stay threshold.
         */
        fun showAttentionNudge(context: Context, message: String): Boolean {
            if (message.isBlank()) return false
            val live = liveService
            if (live != null) {
                live.scope.launch(Dispatchers.Main.immediate) {
                    live.avatar?.react("watch", live.idleMood())
                    live.showTransient(message.take(80), 8_000L)
                }
                return true
            }
            if (!Settings.canDrawOverlays(context)) return false
            val intent = Intent(context, FloatingPresenceService::class.java)
                .putExtra(EXTRA_MESSAGE, message.take(80))
                .putExtra(EXTRA_MODE, FloatingPresenceMode.LIFE.name)
                .putExtra(EXTRA_ATTENTION_NUDGE, true)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        /** Refresh the same running Q view after the approved art pack imports. */
        fun refreshArtworkAfterImport() {
            liveService?.avatar?.reloadArtwork()
        }

        /** The editor/menu stays unchanged. Only the draggable Q image scales. */
        fun setAvatarSize(context: Context, requestedDp: Int): Int {
            val value = QAvatarScale.put(context, requestedDp)
            val live = liveService
            if (live != null) {
                live.scope.launch {
                    live.avatar?.setSizeDp(value)
                    live.requestLayout()
                }
            }
            return value
        }

        /** Return the SAME Q to everyday mode; never remove its four actions. */
        suspend fun stopStudyIfActive(context: Context) {
            val live = liveService
            if (live != null) {
                withContext(Dispatchers.Main.immediate) { live.leaveStudyMode() }
            }
            val repo = PresenceDevicePreferencesRepository(context.applicationContext)
            val preferences = repo.load()
            if (preferences.restoreOverlay &&
                preferences.overlayMode == FloatingPresenceMode.STUDY.name
            ) {
                repo.setOverlayState(true, FloatingPresenceMode.LIFE.name, "给你看")
            }
        }

        private const val CHANNEL_ID = "jlz_presence_overlay_v3"
        private const val NOTIFICATION_ID = 4521
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ATTENTION_NUDGE = "attention_nudge"

        fun start(
            context: Context,
            message: String = "",
            mode: FloatingPresenceMode = FloatingPresenceMode.LIFE
        ): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            val resolvedMessage = message.ifBlank {
                when (mode) {
                    FloatingPresenceMode.LIFE -> "给你看"
                    FloatingPresenceMode.STUDY -> "截题给我"
                    FloatingPresenceMode.FOCUS -> "我在"
                }
            }
            val intent = Intent(context, FloatingPresenceService::class.java)
                .putExtra(EXTRA_MESSAGE, resolvedMessage)
                .putExtra(EXTRA_MODE, mode.name)
            CoroutineScope(Dispatchers.IO).launch {
                PresenceDevicePreferencesRepository(context.applicationContext)
                    .setOverlayState(true, mode.name, resolvedMessage)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                PresenceDevicePreferencesRepository(context.applicationContext)
                    .setRestoreOverlay(false)
            }
            context.stopService(Intent(context, FloatingPresenceService::class.java))
        }
    }
}
