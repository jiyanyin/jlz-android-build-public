package dev.jlz.presence.ui

import dev.jlz.presence.ui.components.WorldText as Text

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jlz.presence.navigation.PresenceRoute
import dev.jlz.presence.navigation.PresenceRouteBus
import dev.jlz.presence.ui.theme.GlassBorder
import dev.jlz.presence.ui.theme.ParchmentGlass
import dev.jlz.presence.ui.theme.ParchmentInk
import dev.jlz.presence.ui.theme.ParchmentMineBubble
import dev.jlz.presence.ui.theme.ParchmentMuted

/**
 * Parchment bottom navigation is a visual gateway to EXISTING screens.
 * It does not recreate message, timeline, status or launcher repositories.
 */
@Composable
internal fun ParchmentBottomBar(route: PresenceRoute) {
    val isHome = route is PresenceRoute.Home
    val isChat = route is PresenceRoute.Chat
    val isTimeline = route is PresenceRoute.TimeChain || route is PresenceRoute.Echo
    val isStatus = route is PresenceRoute.Between && route.tab == "status"
    val isMore = route is PresenceRoute.More || route is PresenceRoute.Drawer ||
        route is PresenceRoute.Today
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = ParchmentInk,
        selectedTextColor = ParchmentInk,
        indicatorColor = ParchmentMineBubble,
        unselectedIconColor = ParchmentMuted,
        unselectedTextColor = ParchmentMuted
    )
    NavigationBar(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, GlassBorder),
        containerColor = ParchmentGlass,
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = isHome,
            onClick = { PresenceRouteBus.open(PresenceRoute.Home) },
            icon = { Icon(Icons.Outlined.Home, contentDescription = "首页") },
            label = { Text("首页") }, colors = colors
        )
        NavigationBarItem(
            selected = isChat,
            onClick = { PresenceRouteBus.open(PresenceRoute.Chat()) },
            icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "聊天") },
            label = { Text("聊天") }, colors = colors
        )
        NavigationBarItem(
            selected = isTimeline,
            onClick = { PresenceRouteBus.open(PresenceRoute.TimeChain) },
            icon = { Icon(Icons.Outlined.Schedule, contentDescription = "时序") },
            label = { Text("时序") }, colors = colors
        )
        NavigationBarItem(
            selected = isStatus,
            onClick = { PresenceRouteBus.open(PresenceRoute.Between("status")) },
            icon = { Icon(Icons.Outlined.Lightbulb, contentDescription = "状态灯") },
            label = { Text("状态灯") }, colors = colors
        )
        NavigationBarItem(
            selected = isMore,
            onClick = { PresenceRouteBus.open(PresenceRoute.More) },
            icon = { Icon(Icons.Outlined.MoreHoriz, contentDescription = "更多") },
            label = { Text("更多") }, colors = colors
        )
    }
}
