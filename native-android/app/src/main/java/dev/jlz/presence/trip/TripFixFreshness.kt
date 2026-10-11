package dev.jlz.presence.trip

object TripFixFreshness {
    /** A cached fix is never relabelled as the time its callback arrived. */
    fun acceptable(fixTimeMs: Long, fixElapsedNanos: Long, nowMs: Long, nowElapsedNanos: Long, startedMs: Long): Boolean {
        val ageNanos = nowElapsedNanos - fixElapsedNanos
        return fixTimeMs >= startedMs && fixTimeMs <= nowMs + 2000 &&
            nowMs - fixTimeMs <= 90_000 && fixElapsedNanos > 0 && ageNanos in 0..90_000_000_000L
    }
}
