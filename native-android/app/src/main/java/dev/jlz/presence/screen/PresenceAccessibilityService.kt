package dev.jlz.presence.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.jlz.presence.capture.AutomaticCaptureCoordinator
import dev.jlz.presence.focus.FocusRepository
import dev.jlz.presence.focus.FocusGateActivity
import dev.jlz.presence.focus.FocusState
import dev.jlz.presence.overlay.FloatingPresenceMode
import dev.jlz.presence.overlay.FloatingPresenceService
import dev.jlz.presence.usage.ForegroundUsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class PresenceAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var focusState: FocusState = FocusState()
    private lateinit var focusRepository: FocusRepository
    private lateinit var automaticCapture: AutomaticCaptureCoordinator
    private val lastGateAtMs = mutableMapOf<String, Long>()
    private val observationCache = AccessibilityObservationCache()
    @Volatile private var contentChangePending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        ScreenObservationBus.setConnected(true)
        AccessibilityScreenshotGateway.bind(this)
        AccessibilityActionGateway.bind(this)
        ForegroundUsageTracker.bind(applicationContext)
        focusRepository = FocusRepository(applicationContext)
        automaticCapture = AutomaticCaptureCoordinator(applicationContext)
        scope.launch {
            focusRepository.state.collectLatest { state -> focusState = state }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
        val eventType = event?.eventType ?: return
        val now = System.currentTimeMillis()
        AccessibilityActionGateway.observeEventSource(event)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityActionGateway.observeWindow(packageName, event.className?.toString())
        }
        if (::automaticCapture.isInitialized) {
            automaticCapture.onAccessibilitySignal(packageName, eventType, now)
        }
        ForegroundUsageTracker.observe(packageName, now)
        val currentFocus = focusState
        if (currentFocus.active && !currentFocus.isActiveNow()) {
            scope.launch { focusRepository.stop() }
        }
        if (packageName != applicationContext.packageName && currentFocus.blocks(packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            FloatingPresenceService.start(applicationContext, "回来，先做完这段。", FloatingPresenceMode.FOCUS)
            val last = lastGateAtMs[packageName] ?: 0L
            if (packageName != null && now - last >= 2_500L) {
                lastGateAtMs[packageName] = now
                FocusGateActivity.show(applicationContext, packageName, currentFocus.reason)
            }
            return
        }
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                observationCache.onWindowStateChanged(packageName, now)
                publishLightSnapshot(packageName, now)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                observationCache.onContentChanged(now)
                if (!contentChangePending) {
                    contentChangePending = true
                    scope.launch {
                        delay(300L)
                        contentChangePending = false
                        if (observationCache.shouldSnapshot(now)) publishLightSnapshot(packageName, now)
                    }
                }
            }
        }
    }

    private fun publishLightSnapshot(packageName: String?, now: Long) {
        val root = rootInActiveWindow ?: return
        val text = collectVisibleText(root)
        observationCache.onSnapshotTaken(now)
        ScreenObservationBus.publish(ScreenObservation(packageName = packageName, visibleText = text, observedAtMs = now))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ForegroundUsageTracker.unbind()
        if (::automaticCapture.isInitialized) automaticCapture.close()
        AccessibilityActionGateway.unbind(this)
        AccessibilityScreenshotGateway.unbind(this)
        ScreenObservationBus.setConnected(false)
        ScreenObservationBus.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val parts = ArrayList<String>()
        var seen = 0
        while (queue.isNotEmpty() && parts.size < 60 && seen < 200) {
            val node = queue.removeFirst()
            seen++
            if (!node.isPassword) {
                val value = node.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) parts += value.take(120)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return parts.distinct().joinToString("\n").take(3000)
    }
}
