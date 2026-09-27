package dev.jlz.presence.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class GestureResult(val accepted: Boolean, val completed: Boolean, val cancelled: Boolean, val detail: String)

/** Accessibility is the phone-side DOM and gesture transport. */
object AccessibilityActionGateway {
    @Volatile private var service: AccessibilityService? = null
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var foregroundActivity: String? = null
    private data class EventFallback(
        val root: AccessibilityNodeInfo,
        val observedAtMs: Long,
        val eventType: Int
    )
    private val eventFallbacks = ConcurrentHashMap<String, EventFallback>()

    internal fun bind(value: AccessibilityService) { service = value }
    internal fun unbind(value: AccessibilityService) { if (service === value) service = null }
    internal fun observeEventSource(event: AccessibilityEvent) {
        val source = runCatching { event.source }.getOrNull() ?: return
        val pkg = source.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return

        // Event sources can remain rich even when an app exposes a package-only
        // 0x0 placeholder through window.root/rootInActiveWindow. Walk upward
        // and keep the richest node rather than blindly taking the top parent.
        var best = source
        var bestScore = eventNodeScore(source)
        var cursor: AccessibilityNodeInfo? = source
        var hops = 0
        while (cursor != null && hops++ < 24) {
            val score = eventNodeScore(cursor)
            if (score > bestScore) {
                best = cursor
                bestScore = score
            }
            cursor = runCatching { cursor.parent }.getOrNull()
        }
        if (bestScore <= 0L) return

        val snapshot = runCatching { AccessibilityNodeInfo.obtain(best) }.getOrNull() ?: best
        val prior = eventFallbacks.put(
            pkg,
            EventFallback(
                root = snapshot,
                observedAtMs = System.currentTimeMillis(),
                eventType = event.eventType
            )
        )
        runCatching { prior?.root?.recycle() }

        // Keep the cache deliberately tiny. We only need recent foreground
        // packages, and AccessibilityNodeInfo objects should not accumulate.
        if (eventFallbacks.size > 8) {
            val cutoff = System.currentTimeMillis() - 30_000L
            eventFallbacks.entries
                .filter { it.value.observedAtMs < cutoff }
                .forEach { (key, value) ->
                    if (eventFallbacks.remove(key, value)) {
                        runCatching { value.root.recycle() }
                    }
                }
        }
    }

    internal fun observeWindow(packageName: String?, activityName: String?) {
        if (packageName.isNullOrBlank()) return
        val activeApplicationPackage = runCatching {
            service?.windows
                ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?.root?.packageName?.toString()
        }.getOrNull()

        // Ignore accessibility overlays, keyboards and system panels that emit
        // WINDOW_STATE_CHANGED while another application still owns the active
        // TYPE_APPLICATION window. Otherwise our own floating presence window
        // can overwrite the foreground app (e.g. WeChat) for node fallback.
        if (!activeApplicationPackage.isNullOrBlank() &&
            packageName != activeApplicationPackage) {
            return
        }
        foregroundPackage = packageName
        if (!activityName.isNullOrBlank()) foregroundActivity = activityName
    }
    fun available(): Boolean = service != null
    fun currentPackage(): String? = bestRoot()?.packageName?.toString() ?: foregroundPackage
    fun currentActivity(): String? = foregroundActivity

    /**
     * Some apps expose a package-only placeholder through rootInActiveWindow
     * while their real hierarchy lives on another interactive window. Choose
     * the richest foreground root instead of treating that placeholder as the
     * complete DOM. This is window-size agnostic and therefore also applies to
     * tablets, split screen and dialogs.
     */
    private data class RootCandidate(
        val node: AccessibilityNodeInfo,
        val windowType: Int,
        val active: Boolean,
        val focused: Boolean
    )

    private fun bestRoot(): AccessibilityNodeInfo? {
        val activeService = service ?: return null
        val candidates = runCatching {
            activeService.windows.mapNotNull { window ->
                window.root?.let {
                    RootCandidate(it, window.type, window.isActive, window.isFocused)
                }
            }
        }.getOrDefault(emptyList())

        val windowRoot = candidates.maxByOrNull(::rootScore)?.node
            ?: activeService.rootInActiveWindow
        if (windowRoot != null && !isDegenerateRoot(windowRoot)) return windowRoot

        val fallbackPackage = windowRoot?.packageName?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: foregroundPackage
        val fallbackEntry = fallbackPackage?.let(eventFallbacks::get)
        val fallback = fallbackEntry?.root
        val fallbackFresh = fallbackEntry != null &&
            System.currentTimeMillis() - fallbackEntry.observedAtMs <= 30_000L
        if (fallback != null && fallbackFresh && !isDegenerateRoot(fallback)) {
            return fallback
        }
        return windowRoot
    }

    private fun isDegenerateRoot(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        return node.childCount == 0 &&
            bounds.width() <= 0 && bounds.height() <= 0 &&
            node.className.isNullOrBlank() &&
            node.text.isNullOrBlank() &&
            node.contentDescription.isNullOrBlank()
    }

    private fun eventNodeScore(node: AccessibilityNodeInfo): Long {
        val bounds = Rect().also(node::getBoundsInScreen)
        val area = bounds.width().coerceAtLeast(0).toLong() *
            bounds.height().coerceAtLeast(0).toLong()
        return node.childCount.toLong() * 100_000_000L +
            area +
            (if (!node.className.isNullOrBlank()) 100_000L else 0L) +
            (if (!node.text.isNullOrBlank()) 10_000L else 0L) +
            (if (!node.contentDescription.isNullOrBlank()) 10_000L else 0L) +
            (if (node.isVisibleToUser) 1_000L else 0L)
    }

    private fun rootScore(candidate: RootCandidate): Long {
        val root = candidate.node
        val bounds = Rect().also(root::getBoundsInScreen)
        val area = bounds.width().coerceAtLeast(0).toLong() *
            bounds.height().coerceAtLeast(0).toLong()
        val windowScore = when (candidate.windowType) {
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> -8_000_000_000_000L
            AccessibilityWindowInfo.TYPE_APPLICATION -> 1_000_000_000_000L
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> 750_000_000_000L
            AccessibilityWindowInfo.TYPE_SYSTEM -> 500_000_000_000L
            else -> 0L
        }
        return windowScore +
            (if (candidate.active) 4_000_000_000_000L else 0L) +
            (if (candidate.focused) 2_000_000_000_000L else 0L) +
            (if (root.packageName?.toString() == foregroundPackage) 1_000_000_000L else 0L) +
            root.childCount.toLong() * 100_000_000L +
            area +
            (if (!root.className.isNullOrBlank()) 10_000L else 0L) +
            (if (root.isVisibleToUser) 1_000L else 0L)
    }

    suspend fun globalAction(action: Int): Boolean = withContext(Dispatchers.Main.immediate) {
        service?.performGlobalAction(action) ?: false
    }

    private suspend fun dispatchAwait(desc: GestureDescription): GestureResult = withContext(Dispatchers.Main.immediate) {
        val active = service ?: return@withContext GestureResult(false, false, false, "no_service")
        suspendCancellableCoroutine { cont ->
            val accepted = active.dispatchGesture(desc, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { if (cont.isActive) cont.resume(GestureResult(true, true, false, "completed")) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(GestureResult(true, false, true, "cancelled")) }
            }, null)
            if (!accepted && cont.isActive) cont.resume(GestureResult(false, false, false, "rejected"))
        }
    }

    suspend fun keyguardPinPadVisible(): Boolean = withContext(Dispatchers.Main.immediate) {
        val root = bestRoot() ?: return@withContext false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 700) {
            val node = queue.removeFirst()
            val id = node.viewIdResourceName.orEmpty()
            if (id.endsWith("/key0") || id.endsWith("/key1") ||
                id.contains("pin_entry", true) || id.contains("pinEntry", true)) {
                return@withContext true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        false
    }

    /**
     * Enter a PIN already stored locally on the phone. The PIN itself never
     * appears in the returned result or Runtime command payload.
     */
    suspend fun enterKeyguardPin(pin: String): Pair<Boolean, String> {
        if (!pin.matches(Regex("\\d{4,16}"))) return false to "invalid_local_pin_format"
        var entered = 0
        for (digit in pin) {
            var clicked = false
            val ids = listOf(
                "com.android.systemui:id/key$digit",
                "com.android.systemui:id/digit_$digit",
                "com.android.systemui:id/num_$digit",
                "miui.systemui.plugin:id/key$digit"
            )
            for (id in ids) {
                val result = nodeAction(
                    JSONObject().put("selector", JSONObject()
                        .put("resource_id", id)
                        .put("index", 1)),
                    "click_node"
                )
                if (result.first) {
                    clicked = true
                    break
                }
            }
            if (!clicked) {
                val result = nodeAction(
                    JSONObject().put("selector", JSONObject()
                        .put("package_name", "com.android.systemui")
                        .put("text_exact", digit.toString())
                        .put("index", 1)),
                    "click_node"
                )
                clicked = result.first
            }
            if (!clicked) return false to ("pin_digit_node_not_found_at_position_" + (entered + 1))
            entered++
            delay(85L)
        }

        // Some lock screens auto-submit fixed-length PINs; others expose Enter.
        // Try known confirm nodes, but do not fail solely because none exists.
        val enterIds = listOf(
            "com.android.systemui:id/key_enter",
            "com.android.systemui:id/key_enter_text",
            "com.android.systemui:id/key_enter_icon",
            "com.android.systemui:id/btn_confirm"
        )
        var submitAttempted = false
        for (id in enterIds) {
            val result = nodeAction(
                JSONObject().put("selector", JSONObject()
                    .put("resource_id", id)
                    .put("index", 1)),
                "click_node"
            )
            if (result.first) {
                submitAttempted = true
                break
            }
        }
        if (!submitAttempted) {
            for (label in listOf("确定", "完成", "确认", "OK")) {
                val result = nodeAction(
                    JSONObject().put("selector", JSONObject()
                        .put("package_name", "com.android.systemui")
                        .put("text_exact", label)
                        .put("index", 1)),
                    "click_node"
                )
                if (result.first) {
                    submitAttempted = true
                    break
                }
            }
        }
        return true to JSONObject()
            .put("ok", true)
            .put("entered_digit_count", entered)
            .put("submit_attempted", submitAttempted)
            .put("secret_echoed", false)
            .toString()
    }

    suspend fun tapG(x: Float, y: Float): GestureResult {
        val p = Path().apply { moveTo(x, y) }
        return dispatchAwait(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, 80L)).build())
    }
    suspend fun doubleTap(x: Float, y: Float): GestureResult {
        val p = Path().apply { moveTo(x, y) }
        return dispatchAwait(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, 60L)).addStroke(GestureDescription.StrokeDescription(p, 120L, 60L)).build())
    }
    suspend fun longPress(x: Float, y: Float, dur: Long = 500L): GestureResult {
        val p = Path().apply { moveTo(x, y) }
        return dispatchAwait(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, dur.coerceIn(350L, 5_000L))).build())
    }
    suspend fun swipeG(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 350L): GestureResult {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchAwait(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, dur.coerceIn(100L, 4_000L))).build())
    }
    suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 600L): GestureResult =
        swipeG(x1, y1, x2, y2, dur.coerceIn(200L, 5_000L))
    suspend fun twoFingerSwipe(cx: Float, cy: Float, dx: Float, dy: Float, dur: Long = 300L): GestureResult {
        val off = 60f
        val p1 = Path().apply { moveTo(cx-off, cy); lineTo(cx-off+dx, cy+dy) }
        val p2 = Path().apply { moveTo(cx+off, cy); lineTo(cx+off+dx, cy+dy) }
        return dispatchAwait(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p1, 0L, dur)).addStroke(GestureDescription.StrokeDescription(p2, 0L, dur)).build())
    }
    suspend fun pinch(cx: Float, cy: Float, startSpan: Float, endSpan: Float, dur: Long = 400L): GestureResult {
        val p1 = Path().apply { moveTo(cx-startSpan, cy); lineTo(cx-endSpan, cy) }
        val p2 = Path().apply { moveTo(cx+startSpan, cy); lineTo(cx+endSpan, cy) }
        return dispatchAwait(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p1, 0L, dur)).addStroke(GestureDescription.StrokeDescription(p2, 0L, dur)).build())
    }
    suspend fun tap(x: Float, y: Float): Boolean = tapG(x, y).completed
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean = swipeG(x1, y1, x2, y2, durationMs).completed

    suspend fun visibleNodesJson(): String = withContext(Dispatchers.Main.immediate) {
        val activeService = service
        val root = bestRoot() ?: return@withContext JSONObject()
            .put("ok", false).put("reason", "no_window").put("package_name", currentPackage().orEmpty()).toString()
        val flat = JSONArray()
        val tree = serializeNode(root, "0", null, 0, flat, 0)
        val windows = runCatching { activeService?.windows.orEmpty() }.getOrDefault(emptyList())
        val windowDiagnostics = JSONArray()
        windows.take(12).forEach { window ->
            val wr = window.root
            val bounds = Rect()
            wr?.getBoundsInScreen(bounds)
            windowDiagnostics.put(JSONObject()
                .put("type", window.type)
                .put("active", window.isActive)
                .put("focused", window.isFocused)
                .put("package_name", wr?.packageName?.toString().orEmpty())
                .put("class_name", wr?.className?.toString().orEmpty())
                .put("child_count", wr?.childCount ?: 0)
                .put("bounds", JSONObject()
                    .put("left", bounds.left).put("top", bounds.top)
                    .put("right", bounds.right).put("bottom", bounds.bottom)))
        }
        JSONObject().put("ok", true).put("observed_at_ms", System.currentTimeMillis())
            .put("package_name", root.packageName?.toString().orEmpty())
            .put("activity_name", currentActivity().orEmpty()).put("window_id", root.windowId)
            .put("is_accessibility_tool",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    activeService?.serviceInfo?.isAccessibilityTool == true
                else JSONObject.NULL)
            .put("service_event_types", activeService?.serviceInfo?.eventTypes ?: 0)
            .put("service_flags", activeService?.serviceInfo?.flags ?: 0)
            .put("window_count", windows.size)
            .put("window_diagnostics", windowDiagnostics)
            .put("event_fallback_packages", JSONArray(eventFallbacks.keys().toList().sorted()))
            .put("foreground_package_hint", foregroundPackage.orEmpty())
            .put("event_fallback_for_selected_package", root.packageName?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let(eventFallbacks::get)?.let { fallback ->
                    JSONObject()
                        .put("package_name", root.packageName?.toString().orEmpty())
                        .put("age_ms", System.currentTimeMillis() - fallback.observedAtMs)
                        .put("event_type", fallback.eventType)
                        .put("available", true)
                } ?: JSONObject()
                    .put("package_name", root.packageName?.toString().orEmpty())
                    .put("available", false))
            .put("screen_signature", signatureOf(flat.toString())).put("node_count", flat.length())
            .put("nodes", flat).put("tree", tree).toString()
    }

    suspend fun screenSignature(): String = withContext(Dispatchers.Main.immediate) {
        val root = bestRoot() ?: return@withContext "no_window"
        val parts = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        while (queue.isNotEmpty() && parts.size < 350) {
            val node = queue.removeFirst()
            if (!node.isPassword && node.isVisibleToUser) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                parts += listOf(node.packageName, node.className, node.viewIdResourceName,
                    node.text, node.contentDescription, bounds.flattenToString()).joinToString("|")
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        signatureOf(parts.joinToString("\n"))
    }

    suspend fun findNodesJson(payload: JSONObject): String = withContext(Dispatchers.Main.immediate) {
        val root = bestRoot() ?: return@withContext JSONObject().put("ok", false).put("reason", "no_window").toString()
        val matches = findMatches(root, selector(payload), 101)
        JSONObject().put("ok", true).put("match_count", matches.size)
            .put("ambiguous", matches.size > 1 && !hasExplicitIndex(payload))
            .put("candidates", JSONArray(matches.take(20).map { nodeSummary(it.node, it.path, it.parentPath, it.childIndex) }))
            .toString()
    }

    suspend fun nodeAction(payload: JSONObject, actionName: String, value: String? = null): Pair<Boolean, String> = withContext(Dispatchers.Main.immediate) {
        val root = bestRoot() ?: return@withContext false to result(false, actionName, "no_window")
        val matches = findMatches(root, selector(payload), 101)
        if (matches.isEmpty()) return@withContext false to result(false, actionName, "node_not_found", matches)
        val requested = selector(payload).optInt("index", payload.optInt("index", 0))
        if (matches.size > 1 && requested <= 0) return@withContext false to result(false, actionName, "ambiguous_selector", matches)
        val chosen = matches.getOrNull((requested.coerceAtLeast(1) - 1))
            ?: return@withContext false to result(false, actionName, "index_out_of_range", matches)
        val node = chosen.node
        val targetSummary = nodeSummary(node, chosen.path, chosen.parentPath, chosen.childIndex)
        val beforeSignature = nodeTreeSignature(root)
        val args = Bundle()
        val actionId = when (actionName) {
            "click_node" -> AccessibilityNodeInfo.ACTION_CLICK
            "long_click_node" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            "focus_node" -> AccessibilityNodeInfo.ACTION_FOCUS
            "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "scroll_node" -> if (payload.optString("direction", "forward").equals("backward", true))
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "copy" -> AccessibilityNodeInfo.ACTION_COPY
            "paste" -> AccessibilityNodeInfo.ACTION_PASTE
            "select_all" -> {
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
                AccessibilityNodeInfo.ACTION_SET_SELECTION
            }
            "set_text", "clear_text" -> {
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    if (actionName == "clear_text") "" else value.orEmpty())
                AccessibilityNodeInfo.ACTION_SET_TEXT
            }
            "press_enter" -> if (Build.VERSION.SDK_INT >= 30) AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id else AccessibilityNodeInfo.ACTION_CLICK
            else -> return@withContext false to result(false, actionName, "unsupported_node_action", listOf(chosen))
        }
        var target: AccessibilityNodeInfo? = node
        var performed = false
        var climbed = 0
        while (target != null && climbed <= 6) {
            if (target.isEnabled && target.performAction(actionId, args)) { performed = true; break }
            if (actionName !in setOf("click_node", "long_click_node")) break
            target = target.parent; climbed++
        }
        if (!performed && actionName in setOf("click_node", "long_click_node")) {
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val gesture = if (actionName == "click_node") tapG(bounds.exactCenterX(), bounds.exactCenterY()) else longPress(bounds.exactCenterX(), bounds.exactCenterY())
                performed = gesture.completed
            }
        }
        if (performed) delay(180L)
        val postRoot = bestRoot()
        val postNode = postRoot?.let { findMatches(it, JSONObject().put("node_path", chosen.path), 1).firstOrNull()?.node }
        val readBack = if (postNode?.isPassword == true) null else postNode?.text?.toString()
        val expectedText = if (actionName == "clear_text") "" else value
        val verification = when {
            !performed -> "failed"
            actionName in setOf("set_text", "clear_text") && readBack == expectedText -> "text_read_back_verified"
            actionName in setOf("set_text", "clear_text") -> "executed_text_not_verified"
            postRoot != null && nodeTreeSignature(postRoot) != beforeSignature -> "screen_change_verified"
            else -> "action_accepted_unverified"
        }
        performed to JSONObject().put("ok", performed).put("action", actionName)
            .put("reason", if (performed) "performed" else "action_rejected")
            .put("verification_status", verification)
            .put("target", targetSummary)
            .put("read_back_text", readBack ?: JSONObject.NULL)
            .put("current_package", currentPackage().orEmpty()).toString()
    }

    suspend fun tapRelativeToNode(payload: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.Main.immediate) {
        val root = bestRoot() ?: return@withContext false to result(false, "tap_relative_to_node", "no_window")
        val matches = findMatches(root, selector(payload), 101)
        val requested = selector(payload).optInt("index", payload.optInt("index", 0))
        if (matches.isEmpty()) return@withContext false to result(false, "tap_relative_to_node", "node_not_found")
        if (matches.size > 1 && requested <= 0) return@withContext false to result(false, "tap_relative_to_node", "ambiguous_selector", matches)
        val chosen = matches.getOrNull((requested.coerceAtLeast(1) - 1))
            ?: return@withContext false to result(false, "tap_relative_to_node", "index_out_of_range", matches)
        val bounds = Rect(); chosen.node.getBoundsInScreen(bounds)
        val rx = payload.optDouble("relative_x", 0.5).coerceIn(0.0, 1.0)
        val ry = payload.optDouble("relative_y", 0.5).coerceIn(0.0, 1.0)
        val x = bounds.left + bounds.width() * rx.toFloat()
        val y = bounds.top + bounds.height() * ry.toFloat()
        val gesture = tapG(x, y)
        gesture.completed to JSONObject().put("ok", gesture.completed).put("action", "tap_relative_to_node")
            .put("x", x).put("y", y).put("target", nodeSummary(chosen.node, chosen.path, chosen.parentPath, chosen.childIndex))
            .put("current_package", currentPackage().orEmpty()).toString()
    }

    suspend fun clickText(text: String, match: String = "contains", index: Int = 1): Boolean =
        nodeAction(JSONObject().put("text", text).put("match", match).put("index", index), "click_node").first

    suspend fun inputText(text: String, append: Boolean = false): Boolean = withContext(Dispatchers.Main.immediate) {
        val root = bestRoot() ?: return@withContext false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstNode(root) { it.isEditable && it.isEnabled }
        val value = if (append) target?.text?.toString().orEmpty() + text else text
        target?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }) ?: false
    }

    private data class Match(val node: AccessibilityNodeInfo, val path: String, val parentPath: String?, val childIndex: Int)
    private fun selector(payload: JSONObject): JSONObject = payload.optJSONObject("selector") ?: payload
    private fun hasExplicitIndex(payload: JSONObject): Boolean = selector(payload).has("index") || payload.has("index")

    private fun findMatches(root: AccessibilityNodeInfo, selector: JSONObject, limit: Int): List<Match> {
        val result = ArrayList<Match>()
        val queue = ArrayDeque<Match>(); queue.add(Match(root, "0", null, 0))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 700 && result.size < limit) {
            val item = queue.removeFirst()
            if (matches(item.node, item.path, selector)) result += item
            for (i in 0 until item.node.childCount) item.node.getChild(i)?.let { queue.add(Match(it, "${item.path}/$i", item.path, i)) }
        }
        return result
    }

    private fun matches(node: AccessibilityNodeInfo, path: String, s: JSONObject): Boolean {
        if (s.optString("node_path").takeIf { it.isNotBlank() }?.let { it != path } == true) return false
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val fallback = s.optString("text")
        if (fallback.isNotBlank()) {
            val ok = when (s.optString("match", "contains").lowercase()) {
                "exact" -> text == fallback || desc == fallback
                "starts", "prefix" -> text.startsWith(fallback) || desc.startsWith(fallback)
                else -> text.contains(fallback, true) || desc.contains(fallback, true)
            }
            if (!ok) return false
        }
        if (!matchField(text, s, "text_exact", "exact")) return false
        if (!matchField(text, s, "text_contains", "contains")) return false
        if (!matchField(text, s, "text_starts_with", "starts")) return false
        if (!matchField(desc, s, "content_description", "exact")) return false
        if (!matchField(desc, s, "content_description_contains", "contains")) return false
        val resource = s.optString("resource_id").ifBlank { s.optString("view_id_resource_name") }
        if (resource.isNotBlank() && node.viewIdResourceName != resource) return false
        val klass = s.optString("class").ifBlank { s.optString("class_name") }
        if (klass.isNotBlank() && node.className?.toString() != klass) return false
        val pkg = s.optString("package").ifBlank { s.optString("package_name") }
        if (pkg.isNotBlank() && node.packageName?.toString() != pkg) return false
        for ((key, actual) in listOf("clickable" to node.isClickable, "editable" to node.isEditable,
            "enabled" to node.isEnabled, "focusable" to node.isFocusable, "focused" to node.isFocused,
            "selected" to node.isSelected, "checked" to node.isChecked, "checkable" to node.isCheckable,
            "scrollable" to node.isScrollable, "password" to node.isPassword, "visible_to_user" to node.isVisibleToUser)) {
            if (s.has(key) && s.optBoolean(key) != actual) return false
        }
        val boundsText = s.optString("bounds")
        if (boundsText.isNotBlank()) { val bounds = Rect(); node.getBoundsInScreen(bounds); if (bounds.flattenToString() != boundsText) return false }
        return true
    }

    private fun matchField(actual: String, selector: JSONObject, key: String, mode: String): Boolean {
        if (!selector.has(key)) return true
        val expected = selector.optString(key)
        return when (mode) { "exact" -> actual == expected; "starts" -> actual.startsWith(expected, true); else -> actual.contains(expected, true) }
    }

    private fun serializeNode(node: AccessibilityNodeInfo, path: String, parentPath: String?, childIndex: Int, flat: JSONArray, depth: Int): JSONObject {
        val json = nodeSummary(node, path, parentPath, childIndex)
        flat.put(JSONObject(json.toString()))
        val children = JSONArray()
        if (depth < 40 && flat.length() < 400) for (i in 0 until node.childCount) node.getChild(i)?.let {
            children.put(serializeNode(it, "$path/$i", path, i, flat, depth + 1))
        }
        return json.put("children", children)
    }

    private fun nodeSummary(node: AccessibilityNodeInfo, path: String, parentPath: String?, childIndex: Int): JSONObject {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val actions = JSONArray(); node.actionList.forEach { actions.put(actionName(it.id)) }
        return JSONObject().put("node_path", path).put("parent_path", parentPath ?: JSONObject.NULL)
            .put("child_index", childIndex).put("child_count", node.childCount)
            .put("package_name", node.packageName?.toString().orEmpty()).put("class", node.className?.toString().orEmpty())
            .put("text", if (node.isPassword) "" else node.text?.toString().orEmpty().take(300))
            .put("content_description", node.contentDescription?.toString().orEmpty().take(300)).put("resource_id", node.viewIdResourceName.orEmpty())
            .put("bounds", JSONObject().put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom).put("center_x", bounds.centerX()).put("center_y", bounds.centerY()))
            .put("clickable", node.isClickable).put("editable", node.isEditable).put("enabled", node.isEnabled)
            .put("focusable", node.isFocusable).put("focused", node.isFocused).put("selected", node.isSelected)
            .put("checked", node.isChecked).put("checkable", node.isCheckable).put("scrollable", node.isScrollable)
            .put("password", node.isPassword).put("visible_to_user", node.isVisibleToUser).put("actions", actions)
    }

    private fun actionName(id: Int): String = when (id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"; AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
        AccessibilityNodeInfo.ACTION_FOCUS -> "focus"; AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear_focus"
        AccessibilityNodeInfo.ACTION_SELECT -> "select"; AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "clear_selection"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"; AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityNodeInfo.ACTION_COPY -> "copy"; AccessibilityNodeInfo.ACTION_PASTE -> "paste"
        AccessibilityNodeInfo.ACTION_CUT -> "cut"; AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        else -> "action_$id"
    }

    private fun result(ok: Boolean, action: String, reason: String, matches: List<Match> = emptyList()): String =
        JSONObject().put("ok", ok).put("action", action).put("reason", reason).put("match_count", matches.size)
            .put("candidates", JSONArray(matches.take(20).map { nodeSummary(it.node, it.path, it.parentPath, it.childIndex) }))
            .put("current_package", currentPackage().orEmpty()).toString()

    private fun signatureOf(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    private fun nodeTreeSignature(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        val parts = ArrayList<String>()
        while (queue.isNotEmpty() && parts.size < 350) {
            val node = queue.removeFirst()
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            parts += listOf(node.packageName, node.className, node.viewIdResourceName,
                if (node.isPassword) "" else node.text, node.contentDescription,
                bounds.flattenToString()).joinToString("|")
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return signatureOf(parts.joinToString("\n"))
    }

    private fun firstNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 700) {
            val node = queue.removeFirst(); if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }
}
