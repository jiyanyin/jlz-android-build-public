package dev.jlz.presence.usage

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import dev.jlz.presence.screen.AccessibilityActionGateway
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Lightweight phone-side evidence journal. Raw device history stays local and
 * is not Memory. UsageEvents provide historical screen/app transitions; this
 * journal adds USER_PRESENT/BOOT plus Runtime command provenance.
 */
class DeviceActivityJournal(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    enum class Origin { USER_OR_NON_RUNTIME, JLZ_RUNTIME, WORK_TEST, SYSTEM, UNKNOWN }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE device_events(
                event_id TEXT PRIMARY KEY,
                event_type TEXT NOT NULL,
                wall_clock_timestamp INTEGER NOT NULL,
                elapsed_realtime INTEGER NOT NULL,
                screen_interactive INTEGER NOT NULL,
                lock_state TEXT NOT NULL,
                foreground_package TEXT,
                origin TEXT NOT NULL,
                actor TEXT NOT NULL,
                command_id TEXT,
                intent_id TEXT,
                device_id TEXT NOT NULL,
                device_type TEXT NOT NULL,
                metadata_json TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_device_events_time ON device_events(wall_clock_timestamp DESC)")
        db.execSQL("CREATE INDEX idx_device_events_type_time ON device_events(event_type, wall_clock_timestamp DESC)")
        db.execSQL(
            """CREATE TABLE command_executions(
                command_id TEXT PRIMARY KEY,
                intent_id TEXT,
                origin TEXT NOT NULL,
                actor TEXT NOT NULL,
                controller TEXT NOT NULL,
                action TEXT NOT NULL,
                requested_at_ms INTEGER,
                phone_received_at_ms INTEGER,
                executed_at_ms INTEGER,
                verified_at_ms INTEGER,
                execution_status TEXT NOT NULL,
                verification_status TEXT NOT NULL,
                before_state TEXT NOT NULL,
                after_state TEXT NOT NULL,
                package_name TEXT,
                device_id TEXT NOT NULL,
                device_type TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_command_time ON command_executions(phone_received_at_ms DESC)")
        db.execSQL("CREATE INDEX idx_command_action_time ON command_executions(action, executed_at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun lockState(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isInteractive != true) return "SCREEN_OFF"
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            return if (keyguard.isDeviceSecure) "LOCKED_SECURE" else "LOCKED_NON_SECURE"
        }
        return "UNLOCKED"
    }

    @Synchronized
    fun recordEvent(
        context: Context,
        eventType: String,
        atMs: Long = System.currentTimeMillis(),
        origin: Origin? = null,
        commandId: String? = null,
        intentId: String? = null,
        deviceId: String = DEFAULT_DEVICE_ID,
        deviceType: String = "phone",
        metadata: JSONObject = JSONObject()
    ): String {
        // P0-3 simplification: this Android client cannot unlock the device.
        // OS unlock/present events are attributed to the phone owner by default.
        // No Runtime-command identity matching or extra confirmation chain.
        val ownerUnlock = eventType == "KEYGUARD_HIDDEN" || eventType == "USER_PRESENT"
        val inferred = if (ownerUnlock) Origin.USER_OR_NON_RUNTIME to null
            else inferOrigin(eventType, AccessibilityActionGateway.currentPackage(), atMs)
        val resolved = if (ownerUnlock) Origin.USER_OR_NON_RUNTIME
            else origin ?: inferred.first
        val id = UUID.randomUUID().toString()
        writableDatabase.insert(
            "device_events", null,
            ContentValues().apply {
                put("event_id", id)
                put("event_type", eventType)
                put("wall_clock_timestamp", atMs)
                put("elapsed_realtime", SystemClock.elapsedRealtime())
                put("screen_interactive", if (context.getSystemService(PowerManager::class.java)?.isInteractive == true) 1 else 0)
                put("lock_state", lockState(context))
                put("foreground_package", AccessibilityActionGateway.currentPackage())
                put("origin", resolved.name)
                put("actor", if (ownerUnlock) "user" else actorFor(resolved))
                put("command_id", if (ownerUnlock) null else commandId ?: inferred.second)
                put("intent_id", intentId)
                put("device_id", deviceId)
                put("device_type", deviceType)
                put("metadata_json", metadata.toString())
            }
        )
        prune()
        return id
    }

    @Synchronized
    fun recordCommandReceived(
        commandId: String,
        intentId: String?,
        origin: Origin,
        controller: String,
        action: String,
        requestedAtMs: Long?,
        phoneReceivedAtMs: Long,
        packageName: String?,
        beforeState: JSONObject,
        deviceId: String,
        deviceType: String = "phone"
    ) {
        writableDatabase.insertWithOnConflict(
            "command_executions", null,
            ContentValues().apply {
                put("command_id", commandId)
                put("intent_id", intentId)
                put("origin", origin.name)
                put("actor", actorFor(origin))
                put("controller", controller)
                put("action", action)
                if (requestedAtMs == null) putNull("requested_at_ms") else put("requested_at_ms", requestedAtMs)
                put("phone_received_at_ms", phoneReceivedAtMs)
                putNull("executed_at_ms")
                putNull("verified_at_ms")
                put("execution_status", "received")
                put("verification_status", "not_verified")
                put("before_state", beforeState.toString())
                put("after_state", "{}")
                put("package_name", packageName)
                put("device_id", deviceId)
                put("device_type", deviceType)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        prune()
    }

    @Synchronized
    fun recordCommandResult(
        commandId: String,
        executedAtMs: Long,
        verifiedAtMs: Long,
        executionStatus: String,
        verificationStatus: String,
        afterState: JSONObject
    ) {
        writableDatabase.update(
            "command_executions",
            ContentValues().apply {
                put("executed_at_ms", executedAtMs)
                put("verified_at_ms", verifiedAtMs)
                put("execution_status", executionStatus)
                put("verification_status", verificationStatus)
                put("after_state", afterState.toString())
            },
            "command_id = ?",
            arrayOf(commandId)
        )
    }

    /** Return origin plus matched command ID for an observed OS event. */
    @Synchronized
    fun inferOrigin(eventType: String, packageName: String?, atMs: Long): Pair<Origin, String?> {
        val actions = when (eventType) {
            "SCREEN_ON" -> listOf("wake_screen", "unlock_secure", "unlock_with_local_pin")
            "SCREEN_OFF" -> listOf("screen_off", "phone_screen_off", "lock_screen")
            "KEYGUARD_SHOWN" -> listOf("screen_off", "phone_screen_off", "lock_screen")
            "APP_FOREGROUND_CHANGED" -> listOf("open_app")
            else -> emptyList()
        }
        if (actions.isEmpty()) return Origin.USER_OR_NON_RUNTIME to null
        val placeholders = actions.joinToString(",") { "?" }
        val args = mutableListOf<String>()
        args.addAll(actions)
        args += (atMs - COMMAND_MATCH_WINDOW_MS).toString()
        args += (atMs + COMMAND_MATCH_WINDOW_MS).toString()
        args += atMs.toString()
        readableDatabase.rawQuery(
            """SELECT command_id, origin, package_name
               FROM command_executions
               WHERE action IN ($placeholders)
                 AND COALESCE(executed_at_ms, phone_received_at_ms) BETWEEN ? AND ?
               ORDER BY ABS(COALESCE(executed_at_ms, phone_received_at_ms) - ?) ASC
               LIMIT 8""".trimIndent(),
            args.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val cmdPackage = cursor.getString(2)
                if (eventType == "APP_FOREGROUND_CHANGED" &&
                    !packageName.isNullOrBlank() && !cmdPackage.isNullOrBlank() &&
                    cmdPackage != packageName) continue
                return normalizeOrigin(cursor.getString(1)) to cursor.getString(0)
            }
        }
        return Origin.USER_OR_NON_RUNTIME to null
    }

    fun readScreenTimeline(
        context: Context,
        startMs: Long,
        endMs: Long,
        originFilter: String? = null,
        limit: Int = 200,
        cursor: String? = null,
        deviceId: String = DEFAULT_DEVICE_ID
    ): JSONObject {
        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = endMs.coerceAtLeast(safeStart)
        val all = mutableListOf<JSONObject>()
        if (!SystemUsageSnapshot.hasPermission(context)) {
            return JSONObject().put("ok", false).put("reason", "usage_access_not_granted")
                .put("device_id", deviceId).put("device_type", "phone").put("items", JSONArray())
        }
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val events = manager?.queryEvents(safeStart, safeEnd)
        if (events != null) {
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val type = when {
                    Build.VERSION.SDK_INT >= 28 && e.eventType == UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_ON"
                    Build.VERSION.SDK_INT >= 28 && e.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_OFF"
                    Build.VERSION.SDK_INT >= 28 && e.eventType == UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
                    Build.VERSION.SDK_INT >= 28 && e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
                    else -> null
                } ?: continue
                val inferred = inferOrigin(type, null, e.timeStamp)
                val origin = inferred.first
                if (!originFilter.isNullOrBlank() && origin.name != originFilter) continue
                val item = JSONObject()
                    .put("event_id", "usage:" + e.timeStamp + ":" + type)
                    .put("event_type", type)
                    .put("wall_clock_timestamp", e.timeStamp)
                    .put("elapsed_realtime", JSONObject.NULL)
                    .put("screen_interactive", type != "SCREEN_OFF")
                    .put("lock_state", if (type == "KEYGUARD_HIDDEN") "UNLOCKED" else JSONObject.NULL)
                    .put("foreground_package", JSONObject.NULL)
                    .put("origin", origin.name).put(
                        "actor", if (type == "KEYGUARD_HIDDEN") "user" else actorFor(origin)
                    )
                    .put("command_id", inferred.second ?: JSONObject.NULL)
                    .put("intent_id", JSONObject.NULL)
                    .put("device_id", deviceId).put("device_type", "phone")
                    .put("source", "android_usage_events")
                if (type == "SCREEN_OFF") {
                    item.put("screen_off_reason",
                        if (origin == Origin.USER_OR_NON_RUNTIME) "NON_RUNTIME_SCREEN_OFF"
                        else if (origin == Origin.UNKNOWN) "UNKNOWN_SCREEN_OFF"
                        else "RUNTIME_OR_TEST_SCREEN_OFF")
                }
                all += item
            }
        }
        appendLocalEvents(all, safeStart, safeEnd,
            setOf("SCREEN_ON", "SCREEN_OFF", "USER_PRESENT", "DEVICE_BOOT",
                "DATA_GAP_START", "DATA_GAP_END"),
            originFilter, deviceId)
        all.sortBy { it.optLong("wall_clock_timestamp") }
        val deduped = dedupeScreenEvents(all)
        return paged("screen_timeline", deduped, limit, cursor, deviceId)
            .put("source", "android_usage_events+device_event_journal")
    }

    fun readAppSessions(
        context: Context,
        startMs: Long,
        endMs: Long,
        packageName: String? = null,
        originFilter: String? = null,
        limit: Int = 200,
        cursor: String? = null,
        deviceId: String = DEFAULT_DEVICE_ID
    ): JSONObject {
        if (!SystemUsageSnapshot.hasPermission(context)) {
            return JSONObject().put("ok", false).put("reason", "usage_access_not_granted")
                .put("device_id", deviceId).put("device_type", "phone").put("sessions", JSONArray())
        }
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return JSONObject().put("ok", false).put("reason", "usage_stats_manager_unavailable")
                .put("sessions", JSONArray())
        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = endMs.coerceAtLeast(safeStart)
        val events = manager.queryEvents(safeStart, safeEnd)
        val event = UsageEvents.Event()
        var currentPackage: String? = null
        var currentStart = 0L
        var currentStartType = ""
        val sessions = mutableListOf<JSONObject>()

        fun close(atMs: Long, backgroundType: String) {
            val pkg = currentPackage ?: return
            if (currentStart <= 0L || atMs <= currentStart) {
                currentPackage = null; currentStart = 0L; return
            }
            if (packageName.isNullOrBlank() || pkg == packageName) {
                val inferred = inferOrigin("APP_FOREGROUND_CHANGED", pkg, currentStart)
                val origin = inferred.first
                if (originFilter.isNullOrBlank() || origin.name == originFilter) {
                    sessions += JSONObject()
                        .put("package_name", pkg)
                        .put("session_start", currentStart).put("session_end", atMs)
                        .put("duration_ms", atMs - currentStart)
                        .put("foreground_event", currentStartType)
                        .put("background_event", backgroundType)
                        .put("origin", origin.name).put("actor", actorFor(origin))
                        .put("command_id", inferred.second ?: JSONObject.NULL)
                        .put("confidence", if (inferred.second != null) "high_command_correlation" else "medium_non_runtime_attribution")
                        .put("source", "android_usage_events")
                        .put("device_id", deviceId).put("device_type", "phone")
                }
            }
            currentPackage = null; currentStart = 0L; currentStartType = ""
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            val at = event.timeStamp.coerceIn(safeStart, safeEnd)
            if (Build.VERSION.SDK_INT >= 28 && type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                close(at, "SCREEN_NON_INTERACTIVE"); continue
            }
            val foreground = type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_RESUMED)
            val background = type == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                (Build.VERSION.SDK_INT >= 29 && (type == UsageEvents.Event.ACTIVITY_PAUSED ||
                    type == UsageEvents.Event.ACTIVITY_STOPPED))
            val pkg = event.packageName?.takeIf { it.isNotBlank() }
            if (foreground && pkg != null) {
                if (currentPackage != null && currentPackage != pkg) close(at, "NEXT_APP_FOREGROUND")
                if (currentPackage == null) {
                    currentPackage = pkg; currentStart = at; currentStartType = eventName(type)
                }
            } else if (background && pkg != null && pkg == currentPackage) {
                close(at, eventName(type))
            }
        }
        close(safeEnd, "QUERY_WINDOW_END")
        sessions.sortBy { it.optLong("session_start") }
        val page = paged("app_sessions", sessions, limit, cursor, deviceId)
        val items = page.optJSONArray("items") ?: JSONArray()
        page.remove("items")
        page.put("sessions", items)
        return page.put("source", "android_usage_events")
    }

    fun readAppUsageTimeline(
        context: Context,
        startMs: Long,
        endMs: Long,
        packageName: String? = null,
        originFilter: String? = null,
        limit: Int = 200,
        cursor: String? = null,
        deviceId: String = DEFAULT_DEVICE_ID
    ): JSONObject = readAppSessions(
        context, startMs, endMs, packageName, originFilter, limit, cursor, deviceId
    ).put("timeline_kind", "foreground_sessions")

    fun phoneRestWindow(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        deviceId: String = DEFAULT_DEVICE_ID
    ): JSONObject {
        val lookback = nowMs - 36L * 60L * 60L * 1000L
        val screen = readScreenTimeline(context, lookback, nowMs, null, 1000, null, deviceId)
        val events = jsonObjects(screen.optJSONArray("items") ?: JSONArray())
        val sessionsJson = readAppSessions(context, lookback, nowMs, null, null, 1000, null, deviceId)
        val sessions = jsonObjects(sessionsJson.optJSONArray("sessions") ?: JSONArray())

        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        cal.set(Calendar.HOUR_OF_DAY, 4); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val morningStart = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 12)
        val morningEnd = cal.timeInMillis

        val firstMorningUnlock = events.firstOrNull {
            it.optString("event_type") == "KEYGUARD_HIDDEN" &&
                it.optLong("wall_clock_timestamp") in morningStart..morningEnd
        }?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }

        val cutoff = firstMorningUnlock ?: nowMs
        val lastNonRuntimeScreenOff = events.lastOrNull {
            it.optString("event_type") == "SCREEN_OFF" &&
                it.optLong("wall_clock_timestamp") <= cutoff &&
                it.optString("origin") !in setOf(Origin.JLZ_RUNTIME.name, Origin.WORK_TEST.name)
        }?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }
        val lastUserPresent = events.lastOrNull {
            it.optString("event_type") == "USER_PRESENT"
        }?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }
        val nonRuntimeSessions = sessions.filter {
            it.optString("origin") !in setOf(Origin.JLZ_RUNTIME.name, Origin.WORK_TEST.name)
        }
        val lastForeground = nonRuntimeSessions.maxOfOrNull { it.optLong("session_end") }
        val lastNonRuntimeInteraction = listOfNotNull(lastUserPresent, lastForeground)
            .filter { it <= cutoff }.maxOrNull()
        val quietStart = listOfNotNull(lastNonRuntimeScreenOff, lastNonRuntimeInteraction).maxOrNull()

        val runtimeAfter = if (quietStart == null) emptyList() else events.filter {
            it.optLong("wall_clock_timestamp") >= quietStart &&
                it.optString("origin") in setOf(Origin.JLZ_RUNTIME.name, Origin.WORK_TEST.name) &&
                it.optString("event_type") in setOf("SCREEN_ON", "SCREEN_OFF")
        }
        val nonRuntimeAfter = if (quietStart == null) emptyList() else events.filter {
            it.optLong("wall_clock_timestamp") > quietStart &&
                it.optString("origin") == Origin.USER_OR_NON_RUNTIME.name &&
                it.optString("event_type") in setOf("SCREEN_ON", "SCREEN_OFF", "USER_PRESENT")
        }
        val evidence = JSONArray()
        quietStart?.let { evidence.put(JSONObject().put("kind", "quiet_window_start").put("at_ms", it)) }
        firstMorningUnlock?.let { evidence.put(JSONObject().put("kind", "first_morning_unlock").put("at_ms", it)) }
        if (runtimeAfter.isNotEmpty()) evidence.put(JSONObject()
            .put("kind", "runtime_screen_events_ignored_for_user_presence").put("count", runtimeAfter.size))

        return JSONObject()
            .put("ok", screen.optBoolean("ok", false))
            .put("classification", "phone_inferred_rest_window")
            .put("medical_sleep_data", false).put("warning", "NOT_MEDICAL_SLEEP_DATA")
            .put("device_id", deviceId).put("device_type", "phone")
            .put("last_non_runtime_screen_off_at", lastNonRuntimeScreenOff ?: JSONObject.NULL)
            .put("last_non_runtime_interaction_at", lastNonRuntimeInteraction ?: JSONObject.NULL)
            .put("last_user_present_at", lastUserPresent ?: JSONObject.NULL)
            .put("last_foreground_activity_at", lastForeground ?: JSONObject.NULL)
            .put("runtime_screen_events_after_that", JSONArray(runtimeAfter))
            .put("non_runtime_screen_events_after_that", JSONArray(nonRuntimeAfter))
            .put("first_morning_unlock_at", firstMorningUnlock ?: JSONObject.NULL)
            .put("quiet_window_start", quietStart ?: JSONObject.NULL)
            .put("quiet_window_end", firstMorningUnlock ?: JSONObject.NULL)
            .put("confidence", if (quietStart != null && firstMorningUnlock != null)
                "medium_phone_evidence" else "low_incomplete_phone_evidence")
            .put("evidence", evidence)
    }

    fun highFrequencySummary(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        deviceId: String = DEFAULT_DEVICE_ID
    ): JSONObject {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val screen = readScreenTimeline(context, cal.timeInMillis, nowMs, null, 1000, null, deviceId)
        val events = jsonObjects(screen.optJSONArray("items") ?: JSONArray())
        val on = events.filter { it.optString("event_type") == "SCREEN_ON" }
        val off = events.filter { it.optString("event_type") == "SCREEN_OFF" }
        val unlock = events.filter { it.optString("event_type") == "KEYGUARD_HIDDEN" }
        val lock = events.filter { it.optString("event_type") == "KEYGUARD_SHOWN" }
        val present = events.filter { it.optString("event_type") == "USER_PRESENT" }
        val lastOn = on.lastOrNull()?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }
        val lastOff = off.lastOrNull()?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }
        val lastNonRuntimeOff = off.lastOrNull {
            it.optString("origin") !in setOf(Origin.JLZ_RUNTIME.name, Origin.WORK_TEST.name)
        }?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }
        val firstDeviceUnlockEvent = unlock.firstOrNull()
        val lastDeviceUnlockEvent = unlock.lastOrNull()
        val firstDeviceUnlock = firstDeviceUnlockEvent
            ?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }
        val lastDeviceUnlock = lastDeviceUnlockEvent
            ?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }
        // Legacy keys remain as aliases only. There is no longer a separate
        // "prove it wasn't the Runtime" unlock classifier.
        val firstNonRuntimeUnlock = firstDeviceUnlock
        val lastNonRuntimeUnlock = lastDeviceUnlock
        val lastLockEvent = lock.lastOrNull()
        val lastLock = lastLockEvent?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }
        val lastPresentEvent = present.lastOrNull()
        val lastPresent = lastPresentEvent?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }
        val lastNonRuntimePresent = lastPresent
        val currentInteractive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val currentStart = if (currentInteractive && lastOn != null && (lastOff == null || lastOn > lastOff)) lastOn else null
        var previous: JSONObject? = null
        if (off.isNotEmpty()) {
            val ended = off.last().optLong("wall_clock_timestamp")
            val started = on.lastOrNull { it.optLong("wall_clock_timestamp") < ended }
                ?.optLong("wall_clock_timestamp")?.takeIf { it > 0L }
            if (started != null && ended > started) {
                previous = JSONObject().put("started_at", started).put("ended_at", ended)
                    .put("duration_ms", ended - started)
                    .put("origin", off.last().optString("origin", Origin.UNKNOWN.name))
            }
        }
        val interactionStart = nowMs - 24L * 60L * 60L * 1000L
        val interactionSessions = jsonObjects(
            readAppSessions(context, interactionStart, nowMs, null, null, 500, null, deviceId)
                .optJSONArray("sessions") ?: JSONArray()
        ).filter {
            it.optString("origin") !in setOf(Origin.JLZ_RUNTIME.name, Origin.WORK_TEST.name)
        }
        val lastForegroundInteraction = interactionSessions.maxOfOrNull { it.optLong("session_end") }
        val lastNonRuntimeInteraction = listOfNotNull(lastNonRuntimePresent, lastForegroundInteraction).maxOrNull()
        return JSONObject()
            .put("last_screen_on_at_ms", lastOn ?: JSONObject.NULL)
            .put("last_screen_off_at_ms", lastOff ?: JSONObject.NULL)
            .put("last_non_runtime_screen_off_at_ms", lastNonRuntimeOff ?: JSONObject.NULL)
            .put("last_lock_at_ms", lastLock ?: JSONObject.NULL)
            .put("last_lock_origin", lastLockEvent?.optString("origin") ?: JSONObject.NULL)
            // Screen-unlock timestamps are owner-default events. Keep older
            // device/non-runtime key names as plain aliases for existing clients.
            .put("last_unlock_at_ms", lastDeviceUnlock ?: JSONObject.NULL)
            .put("last_device_unlock_at_ms", lastDeviceUnlock ?: JSONObject.NULL)
            .put("last_unlock_origin", lastDeviceUnlockEvent?.optString("origin") ?: JSONObject.NULL)
            .put("first_unlock_today_at_ms", firstDeviceUnlock ?: JSONObject.NULL)
            .put("first_device_unlock_today_at_ms", firstDeviceUnlock ?: JSONObject.NULL)
            .put("last_non_runtime_unlock_at_ms", lastNonRuntimeUnlock ?: JSONObject.NULL)
            .put("first_non_runtime_unlock_today_at_ms", firstNonRuntimeUnlock ?: JSONObject.NULL)
            .put("last_user_present_at_ms", lastPresent ?: JSONObject.NULL)
            .put("last_user_present_origin", lastPresentEvent?.optString("origin") ?: JSONObject.NULL)
            .put("last_non_runtime_user_present_at_ms", lastNonRuntimePresent ?: JSONObject.NULL)
            .put("last_non_runtime_interaction_at_ms", lastNonRuntimeInteraction ?: JSONObject.NULL)
            .put("unlock_semantics",
                "KEYGUARD_HIDDEN = user_by_default; non_runtime unlock keys are deprecated direct aliases")
            .put("current_screen_session_started_at_ms", currentStart ?: JSONObject.NULL)
            .put("previous_screen_session", previous ?: JSONObject.NULL)
            .put("lock_state", lockState(context))
    }

    fun usageAttribution(
        context: Context,
        startMs: Long,
        endMs: Long,
        deviceId: String = DEFAULT_DEVICE_ID
    ): JSONObject {
        val sessionsJson = readAppSessions(context, startMs, endMs, null, null, 1000, null, deviceId)
        val sessions = jsonObjects(sessionsJson.optJSONArray("sessions") ?: JSONArray())
        var runtime = 0L; var work = 0L; var nonRuntime = 0L; var unknown = 0L
        sessions.forEach {
            val d = it.optLong("duration_ms").coerceAtLeast(0L)
            when (it.optString("origin")) {
                Origin.JLZ_RUNTIME.name -> runtime += d
                Origin.WORK_TEST.name -> work += d
                Origin.USER_OR_NON_RUNTIME.name -> nonRuntime += d
                else -> unknown += d
            }
        }
        return JSONObject()
            .put("device_foreground_app_time_ms", runtime + work + nonRuntime + unknown)
            .put("known_runtime_foreground_app_time_ms", runtime)
            .put("known_work_test_foreground_app_time_ms", work)
            .put("user_or_non_runtime_foreground_app_time_ms", nonRuntime)
            .put("unknown_foreground_app_time_ms", unknown)
            .put("attribution_method", "android_usage_events+command_time_package_correlation")
            .put("note", "USER_OR_NON_RUNTIME is attribution, not proof of a human operator")
    }

    @Synchronized
    private fun appendLocalEvents(
        target: MutableList<JSONObject>,
        startMs: Long,
        endMs: Long,
        eventTypes: Set<String>,
        originFilter: String?,
        deviceId: String
    ) {
        val placeholders = eventTypes.joinToString(",") { "?" }
        val args = mutableListOf(startMs.toString(), endMs.toString())
        args.addAll(eventTypes)
        readableDatabase.rawQuery(
            """SELECT event_id,event_type,wall_clock_timestamp,elapsed_realtime,
                screen_interactive,lock_state,foreground_package,origin,actor,
                command_id,intent_id,device_id,device_type,metadata_json
               FROM device_events
               WHERE wall_clock_timestamp BETWEEN ? AND ?
                 AND event_type IN ($placeholders)
               ORDER BY wall_clock_timestamp ASC""".trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val origin = c.getString(7)
                if (!originFilter.isNullOrBlank() && origin != originFilter) continue
                if (c.getString(11) != deviceId) continue
                target += JSONObject()
                    .put("event_id", c.getString(0)).put("event_type", c.getString(1))
                    .put("wall_clock_timestamp", c.getLong(2)).put("elapsed_realtime", c.getLong(3))
                    .put("screen_interactive", c.getInt(4) != 0).put("lock_state", c.getString(5))
                    .put("foreground_package", c.getString(6) ?: JSONObject.NULL)
                    .put("origin", origin).put("actor", c.getString(8))
                    .put("command_id", c.getString(9) ?: JSONObject.NULL)
                    .put("intent_id", c.getString(10) ?: JSONObject.NULL)
                    .put("device_id", c.getString(11)).put("device_type", c.getString(12))
                    .put("metadata", runCatching { JSONObject(c.getString(13)) }.getOrElse { JSONObject() })
                    .put("source", "device_event_journal")
            }
        }
    }

    private fun dedupeScreenEvents(events: List<JSONObject>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (event in events) {
            val type = event.optString("event_type")
            val at = event.optLong("wall_clock_timestamp")
            val duplicateIndex = out.indexOfLast { existing ->
                existing.optString("event_type") == type &&
                    kotlin.math.abs(existing.optLong("wall_clock_timestamp") - at) <= 1_500L
            }
            if (duplicateIndex < 0) {
                out += event
                continue
            }
            val existing = out[duplicateIndex]
            val currentLocal = event.optString("source") == "device_event_journal"
            val existingLocal = existing.optString("source") == "device_event_journal"
            if (currentLocal && !existingLocal) {
                out[duplicateIndex] = event
            } else if (currentLocal == existingLocal) {
                val currentCommand = event.opt("command_id") != null &&
                    event.opt("command_id") != JSONObject.NULL
                val existingCommand = existing.opt("command_id") != null &&
                    existing.opt("command_id") != JSONObject.NULL
                if (currentCommand && !existingCommand) out[duplicateIndex] = event
            }
        }
        return out.sortedBy { it.optLong("wall_clock_timestamp") }
    }

    private fun paged(kind: String, all: List<JSONObject>, limit: Int, cursor: String?, deviceId: String): JSONObject {
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val safeLimit = limit.coerceIn(1, 500)
        val page = all.drop(offset).take(safeLimit)
        val next = if (offset + page.size < all.size) (offset + page.size).toString() else ""
        return JSONObject().put("ok", true).put("kind", kind)
            .put("device_id", deviceId).put("device_type", "phone")
            .put("items", JSONArray(page)).put("count", page.size).put("next_cursor", next)
    }

    @Synchronized
    private fun prune() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        writableDatabase.delete("device_events", "wall_clock_timestamp < ?", arrayOf(cutoff.toString()))
        writableDatabase.delete(
            "command_executions",
            "COALESCE(verified_at_ms, executed_at_ms, phone_received_at_ms, requested_at_ms, 0) < ?",
            arrayOf(cutoff.toString())
        )
    }

    companion object {
        const val DEFAULT_DEVICE_ID = "android-phone-native-n0"
        const val RETENTION_DAYS = 14
        private const val DB_NAME = "jlz_device_activity_v1.db"
        private const val DB_VERSION = 1
        private const val COMMAND_MATCH_WINDOW_MS = 12_000L
        private const val RETENTION_MS = RETENTION_DAYS * 24L * 60L * 60L * 1000L

        fun normalizeOrigin(raw: String?): Origin = when (raw?.trim()?.uppercase()) {
            "JLZ_RUNTIME", "OFFICIAL_GPT", "GPT", "RUNTIME" -> Origin.JLZ_RUNTIME
            "WORK_TEST", "WORK", "TEST" -> Origin.WORK_TEST
            "SYSTEM" -> Origin.SYSTEM
            "USER_OR_NON_RUNTIME", "USER", "NON_RUNTIME" -> Origin.USER_OR_NON_RUNTIME
            else -> Origin.UNKNOWN
        }

        fun actorFor(origin: Origin): String = origin.name

        private fun eventName(type: Int): String = when (type) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> "MOVE_TO_FOREGROUND"
            UsageEvents.Event.MOVE_TO_BACKGROUND -> "MOVE_TO_BACKGROUND"
            else -> if (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_RESUMED) "ACTIVITY_RESUMED"
                else if (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_PAUSED) "ACTIVITY_PAUSED"
                else if (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_STOPPED) "ACTIVITY_STOPPED"
                else type.toString()
        }

        private fun jsonObjects(array: JSONArray): List<JSONObject> =
            buildList { for (i in 0 until array.length()) array.optJSONObject(i)?.let(::add) }
    }
}
