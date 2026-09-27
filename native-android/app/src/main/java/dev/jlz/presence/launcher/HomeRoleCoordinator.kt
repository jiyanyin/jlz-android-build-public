package dev.jlz.presence.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

class HomeRoleCoordinator(private val context: Context) {
    fun isDefaultHome(): Boolean {
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_HOME) && rm.isRoleHeld(RoleManager.ROLE_HOME)
    }
    fun requestHomeRole(): Intent? {
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
    }
    fun openHomeSettings() {
        context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
