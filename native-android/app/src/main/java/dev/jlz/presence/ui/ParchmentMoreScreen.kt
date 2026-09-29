package dev.jlz.presence.ui

import dev.jlz.presence.ui.components.WorldText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jlz.presence.navigation.PresenceRoute
import dev.jlz.presence.navigation.PresenceRouteBus
import dev.jlz.presence.ui.components.IceButton
import dev.jlz.presence.ui.components.IceGlassCard
import dev.jlz.presence.ui.theme.ParchmentGold
import dev.jlz.presence.ui.theme.TextPrimary
import dev.jlz.presence.ui.theme.TextSecondary

/** Pure routing view: all destination implementations remain untouched. */
@Composable
internal fun ParchmentMoreScreen() {
    var showLifeActionSheet by remember { mutableStateOf(false) }
    if (showLifeActionSheet) {
        LifeActionSheet(
            onDismiss = { showLifeActionSheet = false },
            onSaved = {}
        )
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("THE REST OF OUR WORLD", color = ParchmentGold,
            style = MaterialTheme.typography.labelMedium)
        Text("更多", color = TextPrimary, style = MaterialTheme.typography.headlineMedium)
        Text("把有用的门都留着，只让日常入口更安静。", color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium)
        IceGlassCard {
            Text("我的生活", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            IceButton("生活快捷记录 · 记一下", onClick = {
                showLifeActionSheet = true
            }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            IceButton("全部应用与桌面", onClick = { PresenceRouteBus.open(PresenceRoute.Drawer) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            IceButton("学习专注", onClick = { PresenceRouteBus.open(PresenceRoute.Study) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            IceButton("带着老公 · 同行", onClick = { PresenceRouteBus.open(PresenceRoute.Trip) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            IceButton("随手记 · 写给老公", onClick = { PresenceRouteBus.open(PresenceRoute.Between("moments")) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            IceButton("今日生活 · 真实记录", onClick = { PresenceRouteBus.open(PresenceRoute.Today) }, modifier = Modifier.fillMaxWidth())
        }
        IceGlassCard {
            Text("记录与回响", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            IceButton("我的回响 · 实际回执", onClick = { PresenceRouteBus.open(PresenceRoute.Echo) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            IceButton("手机客观轨迹", onClick = { PresenceRouteBus.open(PresenceRoute.Timeline) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            IceButton("本机速记（仅本地）", onClick = { PresenceRouteBus.open(PresenceRoute.QuickCapture) }, modifier = Modifier.fillMaxWidth())
        }
        IceGlassCard {
            Text("设备与设置", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            IceButton("权限检查", onClick = { PresenceRouteBus.open(PresenceRoute.PermissionDoctor) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            IceButton("链路与诊断", onClick = { PresenceRouteBus.open(PresenceRoute.Diagnostics) }, modifier = Modifier.fillMaxWidth())
        }
    }
}
