package dev.jlz.presence.usage

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

data class AttentionRhythmSnapshot(
    val classification: String,
    val scrollsPerMinute: Int,
    val contentChangesPerMinute: Int,
    val windowChangesPerMinute: Int,
    val distinctPackagesFiveMinutes: Int,
    val averageScrollIntervalMs: Long?,
    val continuousHighSpeedMs: Long,
    val observedAtMs: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("source", "android_accessibility_aggregate")
        .put("classification", classification)
        .put("scrolls_per_minute", scrollsPerMinute)
        .put("content_changes_per_minute", contentChangesPerMinute)
        .put("window_changes_per_minute", windowChangesPerMinute)
        .put("distinct_packages_five_minutes", distinctPackagesFiveMinutes)
        .put("average_scroll_interval_ms", averageScrollIntervalMs ?: JSONObject.NULL)
        .put("estimated_average_dwell_ms", averageScrollIntervalMs ?: JSONObject.NULL)
        .put("continuous_high_speed_ms", continuousHighSpeedMs)
        .put("window_minutes", 5)
        .put("contains_screen_text", false)
        .put("observed_at_ms", observedAtMs)
}

/** In-memory rolling aggregate. Raw accessibility text never enters this model. */
class AttentionRhythmAccumulator {
    private data class Sample(val atMs: Long, val kind: String, val packageName: String)
    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun observe(kind: String, packageName: String, atMs: Long) {
        if (kind !in setOf("scroll", "content", "window") || packageName.isBlank()) return
        samples.addLast(Sample(atMs, kind, packageName))
        prune(atMs)
    }

    @Synchronized
    fun snapshot(nowMs: Long): AttentionRhythmSnapshot {
        prune(nowMs)
        val minute = samples.filter { nowMs - it.atMs in 0L..60_000L }
        val scrollTimes = minute.filter { it.kind == "scroll" }.map { it.atMs }
        val contentChanges = minute.count { it.kind == "content" }
        val windowChanges = minute.count { it.kind == "window" }
        val distinctPackages = samples.map { it.packageName }.distinct().size
        val gaps = scrollTimes.zipWithNext { first, second -> second - first }
            .filter { it >= 0L }
        val averageInterval = gaps.takeIf { it.isNotEmpty() }?.average()?.toLong()
        var highSpeedStart = scrollTimes.lastOrNull() ?: nowMs
        for (index in scrollTimes.lastIndex downTo 1) {
            if (scrollTimes[index] - scrollTimes[index - 1] > 5_000L) break
            highSpeedStart = scrollTimes[index - 1]
        }
        val continuous = if (scrollTimes.size >= 2) {
            (scrollTimes.last() - highSpeedStart).coerceAtLeast(0L)
        } else 0L
        val classification = when {
            (windowChanges >= 6 && distinctPackages >= 3) ||
                (contentChanges >= 40 && scrollTimes.size >= 10) -> "ATTENTION_FRAGMENTED"
            scrollTimes.size >= 20 ||
                (scrollTimes.size >= 12 && continuous >= 45_000L) -> "RAPID_SCROLL"
            scrollTimes.size >= 8 -> "FAST_SCROLL"
            else -> "NORMAL"
        }
        return AttentionRhythmSnapshot(
            classification = classification,
            scrollsPerMinute = scrollTimes.size,
            contentChangesPerMinute = contentChanges,
            windowChangesPerMinute = windowChanges,
            distinctPackagesFiveMinutes = distinctPackages,
            averageScrollIntervalMs = averageInterval,
            continuousHighSpeedMs = continuous,
            observedAtMs = nowMs
        )
    }

    @Synchronized
    private fun prune(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.peekFirst().atMs > 5 * 60_000L) {
            samples.removeFirst()
        }
    }
}

object AttentionRhythmTracker {
    private val accumulator = AttentionRhythmAccumulator()
    private var outbox: PendingActivityEventStore? = null
    private var ownPackage = ""
    private var lastEmittedClassification = "NORMAL"
    private var lastEmittedAtMs = 0L

    @Synchronized
    fun bind(context: Context) {
        ownPackage = context.packageName
        if (outbox == null) outbox = PendingActivityEventStore(context.applicationContext)
    }

    @Synchronized
    fun observe(packageName: String?, eventType: Int, atMs: Long = System.currentTimeMillis()) {
        val pkg = packageName.orEmpty()
        if (pkg.isBlank() || pkg == ownPackage) return
        val kind = when (eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "window"
            else -> return
        }
        accumulator.observe(kind, pkg, atMs)
        val snapshot = accumulator.snapshot(atMs)
        if (snapshot.classification != lastEmittedClassification &&
            atMs - lastEmittedAtMs >= 60_000L) {
            outbox?.enqueue(
                id = UUID.randomUUID().toString(),
                source = "android_accessibility_aggregate",
                kind = "scroll_behavior",
                title = snapshot.classification,
                body = pkg,
                packageName = pkg,
                metadata = snapshot.toJson(),
                observedAtMs = atMs
            )
            lastEmittedClassification = snapshot.classification
            lastEmittedAtMs = atMs
        }
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis()): JSONObject =
        accumulator.snapshot(nowMs).toJson()
}
