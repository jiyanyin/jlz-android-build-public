package dev.jlz.presence.screen

class AccessibilityObservationCache {
    @Volatile var packageName: String? = null
        private set
    @Volatile private var lastContentChangeAt = 0L
    @Volatile private var lastSnapshotAt = 0L

    fun onWindowStateChanged(pkg: String?, now: Long) { packageName = pkg }
    fun onContentChanged(now: Long) { lastContentChangeAt = now }
    fun onSnapshotTaken(now: Long) { lastSnapshotAt = now }

    fun shouldSnapshot(now: Long): Boolean {
        if (now - lastSnapshotAt < 400L) return false
        return lastContentChangeAt > lastSnapshotAt
    }
}
