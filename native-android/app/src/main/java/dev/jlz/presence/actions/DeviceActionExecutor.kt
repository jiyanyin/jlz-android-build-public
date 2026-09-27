package dev.jlz.presence.actions

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.view.WindowManager
import dev.jlz.presence.notification.PresenceNotificationListenerService
import dev.jlz.presence.runtime.RuntimeCommand
import dev.jlz.presence.usage.DeviceActivityJournal
import dev.jlz.presence.screen.AccessibilityActionGateway
import dev.jlz.presence.security.LocalUnlockSecretStore
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class DeviceActionExecutor(private val context: Context) {
    private val system = DeviceSystemController(context.applicationContext)
    private val intents = AndroidIntentController(context.applicationContext)
    private val activityJournal = DeviceActivityJournal(context.applicationContext)
    private val unlockSecretStore = LocalUnlockSecretStore(context.applicationContext)

    suspend fun execute(command: RuntimeCommand): Pair<Boolean, String> {
        return when (command.action) {
        "open_app" -> openApp(command.packageName ?: knownPackage(command.appName), command.payload.optLong("verify_timeout_ms", 5_000L))
        "set_alarm" -> setSystemAlarm(command.payload)
        "home" -> globalVerified(AccessibilityService.GLOBAL_ACTION_HOME, "home")
        "back" -> globalVerified(AccessibilityService.GLOBAL_ACTION_BACK, "back")
        "recents" -> globalVerified(AccessibilityService.GLOBAL_ACTION_RECENTS, "recents")
        "open_notifications" -> globalVerified(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notifications", "com.android.systemui")
        "open_quick_settings" -> globalVerified(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "quick_settings", "com.android.systemui")
        "screen_off", "phone_screen_off", "lock_screen" -> lockScreen()
        "wake_screen" -> system.wakeScreen().let { it.first to status(it.first, it.second, system.systemState()) }
        "unlock_if_non_secure" -> unlockIfNonSecure(command.payload.optLong("timeout_ms", 4_000L))
        "unlock_secure", "unlock_with_local_pin" -> unlockSecure(command.payload.optLong("timeout_ms", 8_000L))
        "get_lock_state" -> true to JSONObject().put("ok", true).put("lock_state", system.lockState().name)
            .put("requires_user_unlock", system.lockState() == DeviceSystemController.LockState.LOCKED_SECURE).toString()
        "get_native_capabilities" -> true to system.capabilities()
            .put("secure_unlock_supported", true)
            .put("secure_unlock_configured", unlockSecretStore.isConfigured())
            .put("secure_unlock_secret_transport", "device_local_keystore_only")
            .put("device_timeline_ready", true)
            .put("screen_timeline_ready", true)
            .put("app_sessions_ready", true)
            .put("phone_rest_window_ready", true)
            .put("device_activity_retention_days", DeviceActivityJournal.RETENTION_DAYS)
            .put("ok", true).toString()
        "get_system_state" -> true to system.systemState()
            .put("device_activity", activityJournal.highFrequencySummary(context, deviceId = command.deviceId))
            .put("ok", true).toString()
        "read_device_timeline", "read_screen_timeline" -> {
            val endMs = command.payload.optLong("end_time_ms",
                command.payload.optLong("end_time", System.currentTimeMillis()))
            val startMs = command.payload.optLong("start_time_ms",
                command.payload.optLong("start_time", endMs - 24L * 60L * 60L * 1000L))
            val result = activityJournal.readScreenTimeline(
                context = context, startMs = startMs, endMs = endMs,
                originFilter = command.payload.optString("origin").takeIf { it.isNotBlank() },
                limit = command.payload.optInt("limit", 200),
                cursor = command.payload.optString("cursor").takeIf { it.isNotBlank() },
                deviceId = command.deviceId
            )
            result.optBoolean("ok", false) to result.toString()
        }
        "get_phone_rest_window" -> {
            val result = activityJournal.phoneRestWindow(context, deviceId = command.deviceId)
            result.optBoolean("ok", false) to result.toString()
        }
        "read_app_usage_timeline", "read_app_sessions" -> {
            val endMs = command.payload.optLong("end_time_ms",
                command.payload.optLong("end_time", System.currentTimeMillis()))
            val startMs = command.payload.optLong("start_time_ms",
                command.payload.optLong("start_time", endMs - 24L * 60L * 60L * 1000L))
            val pkg = command.payload.optString("package_name")
                .ifBlank { command.payload.optString("package") }
                .takeIf { it.isNotBlank() }
            val result = if (command.action == "read_app_usage_timeline") {
                activityJournal.readAppUsageTimeline(
                    context, startMs, endMs, pkg,
                    command.payload.optString("origin").takeIf { it.isNotBlank() },
                    command.payload.optInt("limit", 200),
                    command.payload.optString("cursor").takeIf { it.isNotBlank() },
                    command.deviceId
                )
            } else {
                activityJournal.readAppSessions(
                    context, startMs, endMs, pkg,
                    command.payload.optString("origin").takeIf { it.isNotBlank() },
                    command.payload.optInt("limit", 200),
                    command.payload.optString("cursor").takeIf { it.isNotBlank() },
                    command.deviceId
                )
            }
            result.optBoolean("ok", false) to result.toString()
        }
        "tap" -> gestureTap(command.payload, false)
        "double_tap" -> gestureTap(command.payload, true)
        "long_press" -> {
            val (x, y) = point(command.payload, "x", "y") ?: return false to status(false, "coords_required")
            val r = AccessibilityActionGateway.longPress(x, y, command.payload.optLong("duration_ms", 650L))
            r.completed to status(r.completed, "long_press_${r.detail}")
        }
        "swipe", "drag" -> {
            val coords = line(command.payload) ?: return false to status(false, "coords_required")
            val r = if (command.action == "drag") AccessibilityActionGateway.drag(coords[0], coords[1], coords[2], coords[3], command.payload.optLong("duration", 650L))
                else AccessibilityActionGateway.swipeG(coords[0], coords[1], coords[2], coords[3], command.payload.optLong("duration", 350L))
            r.completed to status(r.completed, "${command.action}_${r.detail}")
        }
        "two_finger_swipe" -> {
            val cx = command.payload.optDouble("center_x", Double.NaN); val cy = command.payload.optDouble("center_y", Double.NaN)
            val r = AccessibilityActionGateway.twoFingerSwipe(cx.toFloat(), cy.toFloat(), command.payload.optDouble("dx", 0.0).toFloat(), command.payload.optDouble("dy", 0.0).toFloat())
            r.completed to status(r.completed, "two_finger_${r.detail}")
        }
        "pinch" -> {
            val cx = command.payload.optDouble("center_x", Double.NaN); val cy = command.payload.optDouble("center_y", Double.NaN)
            val r = AccessibilityActionGateway.pinch(cx.toFloat(), cy.toFloat(), command.payload.optDouble("start_span", 200.0).toFloat(), command.payload.optDouble("end_span", 80.0).toFloat())
            r.completed to status(r.completed, "pinch_${r.detail}")
        }
        "wait_ms" -> { delay(command.payload.optLong("ms", 500L).coerceIn(0L, 30_000L)); true to status(true, "waited") }
        "wait_for_text", "wait_for_node", "wait_for_node_gone", "wait_for_package", "wait_for_screen_change", "wait_for_idle" -> waitCondition(command.action, command.payload)
        "get_screen_nodes" -> { val json = AccessibilityActionGateway.visibleNodesJson(); JSONObject(json).optBoolean("ok") to json }
        "find_nodes" -> { val json = AccessibilityActionGateway.findNodesJson(command.payload); JSONObject(json).optBoolean("ok") to json }
        "run_sequence", "native_phone_sequence" -> runSequence(command)
        "tap_text" -> {
            val text = command.payload.optString("target_text").ifBlank { command.payload.optString("text") }
            val ok = AccessibilityActionGateway.clickText(text, command.payload.optString("match", "contains"), command.payload.optInt("index", 1))
            ok to status(ok, if (ok) "clicked" else "not_found")
        }
        "input_text" -> {
            val text = command.payload.optString("text").ifBlank { command.payload.optString("input_text") }
            val ok = AccessibilityActionGateway.inputText(text, command.payload.optBoolean("append", false))
            ok to status(ok, if (ok) "inserted" else "no_target")
        }
        "click_node", "long_click_node", "focus_node", "scroll_forward", "scroll_backward", "scroll_node",
        "copy", "paste", "select_all", "clear_text", "press_enter" -> AccessibilityActionGateway.nodeAction(command.payload, command.action)
        "set_text" -> AccessibilityActionGateway.nodeAction(command.payload, "set_text", command.payload.optString("value").ifBlank { command.payload.optString("text") })
        "tap_center_of_node" -> AccessibilityActionGateway.nodeAction(command.payload, "click_node")
        "tap_relative_to_node" -> AccessibilityActionGateway.tapRelativeToNode(command.payload)
        "read_active_notifications", "read_current_notifications" -> {
            val result = PresenceNotificationListenerService.currentNotificationsJson(
                packageName = command.payload.optString("package").ifBlank { command.payload.optString("package_name") }.takeIf { it.isNotBlank() },
                sinceMs = command.payload.optLong("since_ms", 0L),
                activeOnly = command.payload.optBoolean("active_only", command.action == "read_active_notifications"),
                limit = command.payload.optInt("limit", 50)
            )
            result.optBoolean("ok") to result.toString()
        }
        "open_uri", "share_text", "share_file", "share_files", "open_file_picker" -> intents.execute(command.action, command.payload)
        else -> false to status(false, "unsupported:${command.action}")
        }
    }

    private suspend fun gestureTap(payload: JSONObject, double: Boolean): Pair<Boolean, String> {
        val (x, y) = point(payload, "x", "y") ?: return false to status(false, "coords_required")
        val result = if (double) AccessibilityActionGateway.doubleTap(x, y) else AccessibilityActionGateway.tapG(x, y)
        return result.completed to status(result.completed, (if (double) "double_tap_" else "tap_") + result.detail)
    }

    private fun point(payload: JSONObject, xKey: String, yKey: String): Pair<Float, Float>? {
        val metrics = context.resources.displayMetrics
        val width = if (Build.VERSION.SDK_INT >= 30) context.getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics?.bounds?.width() ?: metrics.widthPixels else metrics.widthPixels
        val height = if (Build.VERSION.SDK_INT >= 30) context.getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics?.bounds?.height() ?: metrics.heightPixels else metrics.heightPixels
        val x = when {
            payload.has(xKey) -> payload.optDouble(xKey, Double.NaN)
            payload.has("${xKey}_normalized") -> payload.optDouble("${xKey}_normalized") * width
            payload.has("${xKey}_percent") -> payload.optDouble("${xKey}_percent") / 100.0 * width
            else -> Double.NaN
        }
        val y = when {
            payload.has(yKey) -> payload.optDouble(yKey, Double.NaN)
            payload.has("${yKey}_normalized") -> payload.optDouble("${yKey}_normalized") * height
            payload.has("${yKey}_percent") -> payload.optDouble("${yKey}_percent") / 100.0 * height
            else -> Double.NaN
        }
        if (!x.isFinite() || !y.isFinite()) return null
        return x.toFloat() to y.toFloat()
    }

    private fun line(payload: JSONObject): FloatArray? {
        val p1 = point(payload, "x1", "y1") ?: return null
        val p2 = point(payload, "x2", "y2") ?: return null
        return floatArrayOf(p1.first, p1.second, p2.first, p2.second)
    }

    private suspend fun waitCondition(action: String, payload: JSONObject): Pair<Boolean, String> {
        val timeout = payload.optLong("timeout_ms", 8_000L).coerceIn(100L, 60_000L)
        val poll = payload.optLong("poll_ms", 250L).coerceIn(100L, 2_000L)
        val deadline = System.currentTimeMillis() + timeout
        val startSignature = payload.optString("baseline_signature").ifBlank { AccessibilityActionGateway.screenSignature() }
        var stableSince = System.currentTimeMillis()
        var priorSignature = startSignature
        var last = JSONObject()
        while (System.currentTimeMillis() < deadline) {
            val matched = when (action) {
                "wait_for_package" -> AccessibilityActionGateway.currentPackage() == payload.optString("package").ifBlank { payload.optString("package_name") }
                "wait_for_screen_change" -> AccessibilityActionGateway.screenSignature() != startSignature
                "wait_for_idle" -> {
                    val current = AccessibilityActionGateway.screenSignature()
                    if (current != priorSignature) { priorSignature = current; stableSince = System.currentTimeMillis() }
                    System.currentTimeMillis() - stableSince >= payload.optLong("idle_ms", 750L).coerceIn(250L, 5_000L)
                }
                else -> {
                    val selector = if (action == "wait_for_text" && payload.optJSONObject("selector") == null) {
                        JSONObject(payload.toString()).put("text", payload.optString("text"))
                    } else payload
                    last = JSONObject(AccessibilityActionGateway.findNodesJson(selector))
                    val present = last.optInt("match_count", 0) > 0
                    if (action == "wait_for_node_gone") !present else present
                }
            }
            if (matched) return true to JSONObject().put("ok", true).put("condition", action)
                .put("verification_status", "verified").put("elapsed_ms", timeout - (deadline - System.currentTimeMillis()))
                .put("current_package", AccessibilityActionGateway.currentPackage().orEmpty()).put("nodes", last).toString()
            delay(poll)
        }
        return false to JSONObject().put("ok", false).put("condition", action).put("verification_status", "timeout")
            .put("timeout_ms", timeout).put("current_package", AccessibilityActionGateway.currentPackage().orEmpty()).put("last_nodes", last).toString()
    }

    private suspend fun runSequence(command: RuntimeCommand): Pair<Boolean, String> {
        val steps = command.payload.optJSONArray("steps") ?: return false to status(false, "no_steps")
        val reports = JSONArray(); var allOk = true
        for (i in 0 until minOf(steps.length(), 30)) {
            val step = steps.optJSONObject(i) ?: continue
            val name = step.optString("action")
            if (name.isBlank() || name in setOf("run_sequence", "native_phone_sequence")) {
                allOk = false; reports.put(stepReport(i, name, false, "invalid_nested_action")); break
            }
            val started = System.currentTimeMillis()
            val (ok, detail) = execute(RuntimeCommand(command.id + ":$i", name, step,
                step.optString("package").ifBlank { step.optString("package_name") }.takeIf { it.isNotBlank() },
                step.optString("app").takeIf { it.isNotBlank() }))
            reports.put(stepReport(i, name, ok, detail).put("elapsed_ms", System.currentTimeMillis() - started))
            if (!ok) { allOk = false; if (command.payload.optBoolean("stop_on_error", true)) break }
            val pause = step.optLong("wait_ms", 0L).coerceIn(0L, 5_000L); if (pause > 0) delay(pause)
        }
        return allOk to JSONObject().put("ok", allOk).put("execution_status", if (allOk) "completed" else "failed_stopped")
            .put("executed_steps", reports.length()).put("requested_steps", steps.length()).put("steps", reports).toString()
    }

    private fun stepReport(index: Int, action: String, ok: Boolean, detail: String) = JSONObject()
        .put("step_index", index + 1).put("command", action).put("execution_status", if (ok) "executed" else "failed")
        .put("verification_status", if (ok) "phone_reported_success" else "failed")
        .put("error", if (ok) JSONObject.NULL else detail.take(1_000)).put("result", detail.take(4_000))
        .put("current_package", AccessibilityActionGateway.currentPackage().orEmpty())

    private fun knownPackage(appName: String?): String? = when(appName?.trim()?.lowercase()) {
        "小红书", "xhs" -> "com.xingin.xhs"; "微信", "wechat" -> "com.tencent.mm"
        "chatgpt", "官端" -> "com.openai.chatgpt"; "粉笔" -> "com.fenbi.android.servant"; else -> null
    }

    private fun setSystemAlarm(payload: JSONObject): Pair<Boolean, String> {
        var hour = payload.optInt("hour", -1); var minute = payload.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            val d = payload.optDouble("minutes", -1.0); if (d <= 0) return false to status(false, "alarm_time_required")
            val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, d.toInt()) }
            hour = cal.get(java.util.Calendar.HOUR_OF_DAY); minute = cal.get(java.util.Calendar.MINUTE)
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, payload.optString("message").ifBlank { "纪临洲叫你起床" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true to status(true, "alarm_ok") }
            .getOrElse { false to status(false, "alarm_fail:${it.javaClass.simpleName}") }
    }

    private suspend fun openApp(pkg: String?, timeoutMs: Long): Pair<Boolean, String> {
        if (pkg.isNullOrBlank()) return false to status(false, "pkg_required")
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false to status(false, "no_launch:$pkg")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(launch)
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500L, 15_000L)
        while (System.currentTimeMillis() < deadline) {
            if (AccessibilityActionGateway.currentPackage() == pkg) return true to JSONObject().put("ok", true)
                .put("opened", pkg).put("verification_status", "foreground_package_verified").toString()
            delay(200L)
        }
        // A window-state event can arrive on the timeout boundary. Sample once
        // more before reporting failure so a matching foreground package is
        // never returned alongside a false negative verification status.
        if (AccessibilityActionGateway.currentPackage() == pkg) return true to JSONObject().put("ok", true)
            .put("opened", pkg).put("verification_status", "foreground_package_verified_at_deadline").toString()
        return false to JSONObject().put("ok", false).put("opened_intent", pkg)
            .put("verification_status", "foreground_package_not_verified")
            .put("current_package", AccessibilityActionGateway.currentPackage().orEmpty()).toString()
    }

    private suspend fun unlockSecure(timeoutMs: Long): Pair<Boolean, String> {
        val pin = unlockSecretStore.readPin()
            ?: return false to JSONObject().put("ok", false)
                .put("lock_state", system.lockState().name)
                .put("reason", "local_unlock_pin_not_configured")
                .put("secure_unlock_configured", false)
                .put("verification_status", "not_run")
                .toString()

        val wake = system.wakeScreen()
        if (!wake.first) {
            return false to JSONObject().put("ok", false)
                .put("reason", "wake_failed")
                .put("wake_detail", wake.second)
                .put("verification_status", "wake_failed")
                .toString()
        }

        val wakeDeadline = System.currentTimeMillis() + 2_000L
        while (system.lockState() == DeviceSystemController.LockState.SCREEN_OFF &&
            System.currentTimeMillis() < wakeDeadline) {
            delay(100L)
        }

        // HyperOS needs a short settle after waking before the first keyguard
        // gesture; otherwise the swipe can land during the wake transition.
        if (system.lockState() == DeviceSystemController.LockState.LOCKED_SECURE) {
            delay(650L)
        }

        when (system.lockState()) {
            DeviceSystemController.LockState.UNLOCKED ->
                return true to JSONObject().put("ok", true)
                    .put("lock_state", "UNLOCKED")
                    .put("verification_status", "already_unlocked")
                    .put("secret_source", "device_local_keystore")
                    .toString()
            DeviceSystemController.LockState.LOCKED_NON_SECURE ->
                return unlockIfNonSecure(timeoutMs)
            DeviceSystemController.LockState.SCREEN_OFF ->
                return false to JSONObject().put("ok", false)
                    .put("lock_state", "SCREEN_OFF")
                    .put("reason", "wake_not_confirmed")
                    .put("verification_status", "wake_not_verified")
                    .toString()
            DeviceSystemController.LockState.LOCKED_SECURE -> Unit
        }

        if (!AccessibilityActionGateway.keyguardPinPadVisible()) {
            val start = point(JSONObject().put("x_normalized", 0.5).put("y_normalized", 0.88), "x", "y")!!
            val end = point(JSONObject().put("x_normalized", 0.5).put("y_normalized", 0.24), "x", "y")!!
            val swipe = AccessibilityActionGateway.swipeG(start.first, start.second, end.first, end.second, 420L)
            if (!swipe.completed) {
                return false to JSONObject().put("ok", false)
                    .put("lock_state", system.lockState().name)
                    .put("reason", "pin_pad_reveal_gesture_" + swipe.detail)
                    .put("verification_status", "pin_pad_not_revealed")
                    .toString()
            }
            delay(420L)
        }

        if (!AccessibilityActionGateway.keyguardPinPadVisible()) {
            // Xiaomi/HyperOS can need a second upward reveal after an AOD transition.
            val start = point(JSONObject().put("x_normalized", 0.5).put("y_normalized", 0.86), "x", "y")!!
            val end = point(JSONObject().put("x_normalized", 0.5).put("y_normalized", 0.22), "x", "y")!!
            AccessibilityActionGateway.swipeG(start.first, start.second, end.first, end.second, 380L)
            delay(350L)
        }

        val entered = AccessibilityActionGateway.enterKeyguardPin(pin)
        if (!entered.first) {
            return false to JSONObject().put("ok", false)
                .put("lock_state", system.lockState().name)
                .put("reason", entered.second)
                .put("verification_status", "pin_entry_failed")
                .put("secret_source", "device_local_keystore")
                .toString()
        }

        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_500L, 12_000L)
        while (System.currentTimeMillis() < deadline) {
            if (system.lockState() == DeviceSystemController.LockState.UNLOCKED) {
                return true to JSONObject().put("ok", true)
                    .put("lock_state", "UNLOCKED")
                    .put("execution_status", "executed")
                    .put("verification_status", "secure_unlock_verified")
                    .put("secret_source", "device_local_keystore")
                    .toString()
            }
            delay(120L)
        }
        return false to JSONObject().put("ok", false)
            .put("lock_state", system.lockState().name)
            .put("verification_status", "secure_unlock_not_verified")
            .put("secret_source", "device_local_keystore")
            .toString()
    }

    private suspend fun unlockIfNonSecure(timeoutMs: Long): Pair<Boolean, String> {
        system.wakeScreen()
        when (system.lockState()) {
            DeviceSystemController.LockState.UNLOCKED -> return true to JSONObject().put("ok", true)
                .put("lock_state", "UNLOCKED").put("verification_status", "already_unlocked").toString()
            DeviceSystemController.LockState.LOCKED_SECURE -> return false to JSONObject().put("ok", false)
                .put("lock_state", "LOCKED_SECURE").put("requires_user_unlock", true).toString()
            DeviceSystemController.LockState.SCREEN_OFF -> return false to JSONObject().put("ok", false)
                .put("lock_state", "SCREEN_OFF").put("reason", "wake_not_confirmed").toString()
            DeviceSystemController.LockState.LOCKED_NON_SECURE -> Unit
        }
        val start = point(JSONObject().put("x_normalized", 0.5).put("y_normalized", 0.86), "x", "y")!!
        val end = point(JSONObject().put("x_normalized", 0.5).put("y_normalized", 0.22), "x", "y")!!
        val gesture = AccessibilityActionGateway.swipeG(start.first, start.second, end.first, end.second, 450L)
        if (!gesture.completed) return false to status(false, "unlock_gesture_${gesture.detail}")
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500L, 10_000L)
        while (System.currentTimeMillis() < deadline) {
            if (system.lockState() == DeviceSystemController.LockState.UNLOCKED) return true to JSONObject()
                .put("ok", true).put("lock_state", "UNLOCKED").put("verification_status", "unlocked_verified").toString()
            delay(200L)
        }
        return false to JSONObject().put("ok", false).put("lock_state", system.lockState().name)
            .put("verification_status", "unlock_not_verified")
            .put("requires_user_unlock", system.lockState() == DeviceSystemController.LockState.LOCKED_SECURE).toString()
    }

    private suspend fun lockScreen(): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < 28) return false to status(false, "lock_requires_28")
        val accepted = AccessibilityActionGateway.globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        if (!accepted) return false to status(false, "a11y_global_action_rejected:lock")
        repeat(10) {
            if (system.lockState() != DeviceSystemController.LockState.UNLOCKED) return true to JSONObject()
                .put("ok", true).put("execution_status", "executed").put("verification_status", "lock_state_verified")
                .put("lock_state", system.lockState().name).toString()
            delay(150L)
        }
        return true to JSONObject().put("ok", true).put("execution_status", "executed")
            .put("verification_status", "action_accepted_unverified").put("lock_state", system.lockState().name).toString()
    }

    private suspend fun globalVerified(action: Int, code: String, expectedPackage: String? = null): Pair<Boolean, String> {
        val beforePackage = AccessibilityActionGateway.currentPackage()
        val beforeSignature = AccessibilityActionGateway.screenSignature()
        val accepted = AccessibilityActionGateway.globalAction(action)
        if (!accepted) return false to status(false, "a11y_global_action_rejected:$code")
        val deadline = System.currentTimeMillis() + 2_500L
        var changed = false
        while (System.currentTimeMillis() < deadline) {
            val currentPackage = AccessibilityActionGateway.currentPackage()
            val packageVerified = expectedPackage?.let { currentPackage == it } ?: (currentPackage != beforePackage)
            val screenChanged = AccessibilityActionGateway.screenSignature() != beforeSignature
            if (packageVerified || screenChanged) { changed = true; break }
            delay(150L)
        }
        return true to JSONObject().put("ok", true).put("action", code)
            .put("execution_status", "executed")
            .put("verification_status", if (changed) "screen_change_verified" else "action_accepted_unverified")
            .put("current_package", AccessibilityActionGateway.currentPackage().orEmpty()).toString()
    }

    private fun status(ok: Boolean, reason: String, extra: JSONObject? = null): String = JSONObject()
        .put("ok", ok).put("reason", reason).put("current_package", AccessibilityActionGateway.currentPackage().orEmpty())
        .put("observed_at_ms", System.currentTimeMillis()).also { if (extra != null) it.put("state", extra) }.toString()
}
