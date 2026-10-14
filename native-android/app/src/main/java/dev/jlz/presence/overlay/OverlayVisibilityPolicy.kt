package dev.jlz.presence.overlay

/** Only current, visible windows hide the overlay; stale package events cannot latch it off. */
object OverlayVisibilityPolicy {
    fun protectedPackage(pkg: String) = pkg.contains("permissioncontroller") ||
        pkg.contains("packageinstaller") || pkg.contains("incall") || pkg == "com.android.systemui"
    fun hide(locked: Boolean, interactive: Boolean, capture: Boolean, protectedWindow: Boolean) =
        locked || !interactive || capture || protectedWindow
}
