package dev.jlz.presence.focus

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.jlz.presence.data.LocalLifeStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.focusDataStore by preferencesDataStore(name = "jlz_focus")

data class FocusState(
    val active: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
    val temporaryReleases: Map<String, Long> = emptyMap(),
    val manualAppLocks: Map<String, Long> = emptyMap(),
    val startedAtMs: Long = 0L,
    val endsAtMs: Long = 0L,
    val reason: String = ""
) {
    fun isActiveNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        active && (endsAtMs <= 0L || nowMs < endsAtMs)

    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long =
        if (!isActiveNow(nowMs) || endsAtMs <= 0L) 0L
        else (endsAtMs - nowMs).coerceAtLeast(0L)

    fun isTemporarilyReleased(
        packageName: String?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean =
        !packageName.isNullOrBlank() &&
            (temporaryReleases[packageName] ?: 0L) > nowMs

    fun blocks(
        packageName: String?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean =
        !packageName.isNullOrBlank() &&
            ((isActiveNow(nowMs) && packageName in blockedPackages) ||
                (manualAppLocks[packageName] ?: 0L) > nowMs) &&
            !isTemporarilyReleased(packageName, nowMs)
}

class FocusRepository(private val context: Context) {
    private val lifeStore = LocalLifeStore(context.applicationContext)

    private object Keys {
        val active = booleanPreferencesKey("active")
        val blocked = stringSetPreferencesKey("blocked_packages")
        val temporaryReleases = stringSetPreferencesKey("temporary_releases")
        val manualAppLocks = stringSetPreferencesKey("manual_app_locks_v1")
        val startedAt = longPreferencesKey("started_at_ms")
        val endsAt = longPreferencesKey("ends_at_ms")
        val reason = stringPreferencesKey("reason")
    }

    val state: Flow<FocusState> = context.focusDataStore.data.map { prefs ->
        FocusState(
            active = prefs[Keys.active] ?: false,
            blockedPackages = prefs[Keys.blocked] ?: emptySet(),
            temporaryReleases = decodeReleases(
                prefs[Keys.temporaryReleases] ?: emptySet()
            ),
            manualAppLocks = decodeReleases(
                prefs[Keys.manualAppLocks] ?: emptySet()
            ),
            startedAtMs = prefs[Keys.startedAt] ?: 0L,
            endsAtMs = prefs[Keys.endsAt] ?: 0L,
            reason = prefs[Keys.reason].orEmpty()
        )
    }

    suspend fun current(): FocusState = state.first()

    suspend fun start(
        blockedPackages: Set<String>,
        durationMs: Long,
        reason: String = ""
    ) {
        val now = System.currentTimeMillis()
        context.focusDataStore.edit { prefs ->
            prefs[Keys.active] = true
            prefs[Keys.blocked] = blockedPackages.filter { it.isNotBlank() }.toSet()
            prefs[Keys.temporaryReleases] = emptySet()
            prefs[Keys.startedAt] = now
            prefs[Keys.endsAt] = if (durationMs > 0) now + durationMs else 0L
            prefs[Keys.reason] = reason
        }
        lifeStore.recordTimeline(
            "focus",
            "进入专注",
            reason.ifBlank { "这段时间交给我" }
        )
    }

    suspend fun stop() {
        context.focusDataStore.edit { prefs ->
            prefs[Keys.active] = false
            prefs[Keys.endsAt] = 0L
            prefs[Keys.reason] = ""
            prefs[Keys.temporaryReleases] = emptySet()
        }
        lifeStore.recordTimeline("focus", "结束专注", "Focus Session 已结束")
    }

    suspend fun addBlockedPackage(packageName: String) {
        if (packageName.isBlank()) return
        context.focusDataStore.edit { prefs ->
            val next = (prefs[Keys.blocked] ?: emptySet()) + packageName
            prefs[Keys.blocked] = next
            prefs[Keys.active] = true
            if ((prefs[Keys.startedAt] ?: 0L) == 0L) {
                prefs[Keys.startedAt] = System.currentTimeMillis()
            }
        }
        lifeStore.recordTimeline("focus", "加入门禁", packageName)
    }

    suspend fun removeBlockedPackage(packageName: String) {
        context.focusDataStore.edit { prefs ->
            prefs[Keys.blocked] = (prefs[Keys.blocked] ?: emptySet()) - packageName
        }
        lifeStore.recordTimeline("focus", "移出门禁", packageName)
    }

    /** A timed, per-app lock independent of the current study Focus plan. */
    suspend fun lockAppForDuration(
        packageName: String, durationMs: Long, reason: String = ""
    ): Long {
        require(packageName.isNotBlank()) { "app_lock_package_required" }
        val until = System.currentTimeMillis() +
            durationMs.coerceIn(60_000L, 24L * 60L * 60_000L)
        context.focusDataStore.edit { prefs ->
            val locks = decodeReleases(
                prefs[Keys.manualAppLocks] ?: emptySet()
            ).toMutableMap()
            locks[packageName] = until
            prefs[Keys.manualAppLocks] = encodeReleases(locks)
        }
        lifeStore.recordTimeline("app_gate", "我暂时不让你打开这个 App",
            packageName + " · " + reason.take(150))
        return until
    }

    suspend fun unlockAppGate(packageName: String) {
        require(packageName.isNotBlank()) { "app_unlock_package_required" }
        context.focusDataStore.edit { prefs ->
            val locks = decodeReleases(
                prefs[Keys.manualAppLocks] ?: emptySet()
            ).toMutableMap()
            locks.remove(packageName)
            prefs[Keys.manualAppLocks] = encodeReleases(locks)
            prefs[Keys.blocked] = (prefs[Keys.blocked] ?: emptySet()) - packageName
        }
        lifeStore.recordTimeline("app_gate", "我把这个 App 的门禁打开了", packageName)
    }

    suspend fun grantTemporaryRelease(
        packageName: String,
        durationMs: Long
    ) {
        if (packageName.isBlank()) return
        val until = System.currentTimeMillis() + durationMs.coerceAtLeast(30_000L)
        context.focusDataStore.edit { prefs ->
            val current = decodeReleases(
                prefs[Keys.temporaryReleases] ?: emptySet()
            ).toMutableMap()
            current[packageName] = until
            prefs[Keys.temporaryReleases] = encodeReleases(current)
        }
        lifeStore.recordTimeline(
            "focus",
            "临时放行",
            packageName + " · " + (durationMs / 60_000L) + " 分钟"
        )
    }

    suspend fun revokeTemporaryRelease(packageName: String) {
        context.focusDataStore.edit { prefs ->
            val current = decodeReleases(
                prefs[Keys.temporaryReleases] ?: emptySet()
            ).toMutableMap()
            current.remove(packageName)
            prefs[Keys.temporaryReleases] = encodeReleases(current)
        }
    }

    private fun decodeReleases(raw: Set<String>): Map<String, Long> =
        buildMap {
            raw.forEach { encoded ->
                val split = encoded.lastIndexOf('|')
                if (split <= 0) return@forEach
                val pkg = encoded.substring(0, split)
                val until = encoded.substring(split + 1).toLongOrNull() ?: return@forEach
                put(pkg, until)
            }
        }

    private fun encodeReleases(releases: Map<String, Long>): Set<String> =
        releases.map { (pkg, until) -> pkg + "|" + until }.toSet()
}
