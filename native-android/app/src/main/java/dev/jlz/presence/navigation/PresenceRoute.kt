package dev.jlz.presence.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PresenceRoute {
    data object Home : PresenceRoute
    data object Drawer : PresenceRoute
    data object Timeline : PresenceRoute
    data object Welcome : PresenceRoute
    data object Study : PresenceRoute
    data object Today : PresenceRoute
    data object Trip : PresenceRoute
    data object Diagnostics : PresenceRoute
    data object PermissionDoctor : PresenceRoute
    data object QuickCapture : PresenceRoute
    data class Chat(val eventId: String? = null, val intentId: String? = null) : PresenceRoute
}

object PresenceRouteBus {
    private val mutableRoute = MutableStateFlow<PresenceRoute>(PresenceRoute.Welcome)
    val route: StateFlow<PresenceRoute> = mutableRoute.asStateFlow()
    fun open(route: PresenceRoute) { mutableRoute.value = route }
}
