package dev.jlz.presence.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.app.NotificationManager
import android.net.Uri
import android.provider.Settings
import dev.jlz.presence.usage.SystemUsageSnapshot
import dev.jlz.presence.capture.AutomaticCaptureCoordinator
import dev.jlz.presence.life.NativeCalendarBridge
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.jlz.presence.notification.NotificationSourceRepository
import dev.jlz.presence.notification.HuaweiHealthNotificationReading
import dev.jlz.presence.place.PlaceWeatherCoordinator
import dev.jlz.presence.security.LocalUnlockSecretStore
import dev.jlz.presence.runtime.NativePhoneSnapshot
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import kotlinx.coroutines.launch

@Composable
fun RuntimeIdentityPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { RuntimeSettingsRepository(context.applicationContext) }
    var deviceId by remember { mutableStateOf("正在读取…") }
    var baseUrl by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val deviceType = remember { NativePhoneSnapshot.deviceType(context) }
    val automaticDeviceId = remember(deviceType) {
        if (deviceType == "tablet") "android-tablet-native-n0" else "android-phone-native-n0"
    }

    suspend fun refresh() {
        val settings = repository.load()
        deviceId = settings.deviceId
        baseUrl = settings.baseUrl
        configured = settings.baseUrl.isNotBlank() && settings.token.isNotBlank()
    }

    LaunchedEffect(Unit) { refresh() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Runtime 设备身份", style = MaterialTheme.typography.titleMedium)
            Text(deviceId)
            Text(
                if (deviceType == "tablet") "设备类型：平板（自动分配）"
                else "设备类型：手机（自动分配）",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                if (configured) "Runtime 地址和令牌已配置。"
                else "Runtime 地址或令牌尚未配置。",
                style = MaterialTheme.typography.bodySmall
            )
            Text("设备 ID 由同一 APK 根据设备类型自动选择，不需要手工输入。", style = MaterialTheme.typography.bodySmall)
            if (deviceId != automaticDeviceId) {
                Button(onClick = {
                    scope.launch {
                        val current = repository.load()
                        repository.save(current.copy(deviceId = automaticDeviceId))
                        refresh()
                        message = "已恢复为本机自动设备身份。"
                    }
                }) {
                    Text("恢复自动设备身份")
                }
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Runtime HTTPS 地址") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (configured) "Runtime 令牌（留空则保留现有令牌）" else "Runtime 令牌") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Button(onClick = {
                scope.launch {
                    val normalizedUrl = baseUrl.trim().trimEnd('/')
                    val current = repository.load()
                    val effectiveToken = tokenInput.trim().ifBlank { current.token }
                    if (!normalizedUrl.startsWith("https://")) {
                        message = "Runtime 地址必须以 https:// 开头。"
                    } else if (effectiveToken.isBlank()) {
                        message = "请输入 Runtime 令牌。"
                    } else {
                        repository.save(
                            current.copy(
                                baseUrl = normalizedUrl,
                                token = effectiveToken,
                                deviceId = deviceId
                            )
                        )
                        tokenInput = ""
                        refresh()
                        message = "Runtime 配置已保存，后台将自动重连。"
                    }
                }
            }) {
                Text("保存 Runtime 配置")
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun NotificationSourcesPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { NotificationSourceRepository(context.applicationContext) }
    var seen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allowed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allSources by remember { mutableStateOf(false) }

    suspend fun refresh() {
        seen = repo.seenPackages()
        allowed = repo.allowedPackages()
        allSources = repo.globalEnabled()
    }

    LaunchedEffect(Unit) { refresh() }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("现实通知来源", style = MaterialTheme.typography.titleMedium)
            Text("可只选指定 App，或主动开启全部来源。通知仍由 Android 正常显示，不会被在场拦截。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("读取全部通知来源")
                    Text("只读取系统允许提供的通知文字；开启后不必逐个勾选。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = allSources,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            repo.setGlobalEnabled(enabled)
                            refresh()
                        }
                    }
                )
            }
            Text("华为运动健康常驻步数通知需要先实际出现；来源、时间和可读字段会随记录保存，不代表手环实时心率。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("华为运动健康（通知观察）")
                    Text(HuaweiHealthNotificationReading.PACKAGE_NAME, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = allSources ||
                        HuaweiHealthNotificationReading.PACKAGE_NAME in allowed,
                    enabled = !allSources,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            repo.setAllowed(HuaweiHealthNotificationReading.PACKAGE_NAME, enabled)
                            refresh()
                        }
                    }
                )
            }
            if (seen.isEmpty()) {
                Text("等系统收到过通知后，这里会出现来源 App。")
            } else {
                seen.sorted()
                    .filter { it != HuaweiHealthNotificationReading.PACKAGE_NAME &&
                        it != "org.ikuuu.vpn" }
                    .take(30).forEach { packageName ->
                    val label = remember(packageName) {
                        runCatching {
                            val info = context.packageManager.getApplicationInfo(packageName, 0)
                            context.packageManager.getApplicationLabel(info).toString()
                        }.getOrDefault(packageName)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label)
                            Text(packageName, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = allSources || packageName in allowed,
                            enabled = !allSources,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repo.setAllowed(packageName, checked)
                                    refresh()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaceSettingsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = remember { PlaceWeatherCoordinator(context.applicationContext) }
    var message by remember { mutableStateOf("") }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        message = if (grants.values.any { it }) "位置权限已允许" else "位置权限未允许"
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("生活位置", style = MaterialTheme.typography.titleMedium)
            Text("精确坐标留在手机里；Runtime 默认只拿语义位置和天气。")
            Button(onClick = {
                permission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            }) {
                Text("位置权限")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        message = if (coordinator.setCurrentAsHome()) "这里已记作 HOME"
                        else "暂时拿不到当前位置"
                    }
                }) {
                    Text("这里是家")
                }
                Button(onClick = {
                    scope.launch {
                        message = if (coordinator.setCurrentAsOffice()) "这里已记作 OFFICE"
                        else "暂时拿不到当前位置"
                    }
                }) {
                    Text("这里是单位")
                }
            }
            if (message.isNotBlank()) Text(message)
        }
    }
}

/** Usage Access is a special Android setting, not a runtime permission dialog. */
@Composable
fun UsageSettingsPanel() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(SystemUsageSnapshot.hasPermission(context))
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("手机使用情况", style = MaterialTheme.typography.titleMedium)
            Text(if (granted) "已允许：我能读取系统记录的今日各 APP 使用时长。"
                else "尚未允许：当前只有无障碍观察到的粗略使用记录，无法保证整天的真实时长。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("打开使用情况授权") }
                Button(onClick = { granted = SystemUsageSnapshot.hasPermission(context) }) {
                    Text("重新检查")
                }
            }
        }
    }
}

@Composable
fun CalendarSettingsPanel() {
    val context = LocalContext.current
    val bridge = remember { NativeCalendarBridge(context.applicationContext) }
    var canRead by remember { mutableStateOf(bridge.canRead()) }
    var canWrite by remember { mutableStateOf(bridge.canWrite()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        canRead = bridge.canRead()
        canWrite = bridge.canWrite()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("我的日历", style = MaterialTheme.typography.titleMedium)
            Text(if (canRead) "我已能读取你同意共享的系统日历安排。"
            else "需要你允许我读取日历，我才能在手机上知道什么时候该找你。")
            Text(if (canWrite) "已允许添加日历事件。" else "还没有添加日历事件的权限。")
            Button(onClick = {
                launcher.launch(arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                ))
            }) { Text("允许我读写日历") }
        }
    }
}

@Composable
fun IncomingCallSettingsPanel() {
    val context = LocalContext.current
    val manager = remember {
        context.getSystemService(NotificationManager::class.java)
    }
    var fullScreen by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("「过来」的弹出方式", style = MaterialTheme.typography.titleMedium)
            Text(if (fullScreen) "手机系统允许我用来电式界面找你。"
            else "手机系统暂时不允许全屏来电；我可以发通知，但不保证能直接弹出「过来」。")
            if (Build.VERSION.SDK_INT >= 34) {
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                .setData(Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("允许「过来」全屏显示") }
                Button(onClick = { fullScreen = manager.canUseFullScreenIntent() }) {
                    Text("重新检查来电权限")
                }
            }
        }
    }
}

@Composable
fun AutomaticCaptureSettingsPanel() {
    val context = LocalContext.current
    val coordinator = remember { AutomaticCaptureCoordinator(context.applicationContext) }
    var enabled by remember { mutableStateOf(coordinator.enabled()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("我自己看看你在干什么", style = MaterialTheme.typography.titleMedium)
            Text("你授权后，我在亮屏且无障碍可读取时自动暂存截图：学习时段约每4分钟一张，其他时段约每12分钟一张，按实际学习会话分列。")
            Text("原图先留在手机；上传成功不等于官端GPT已经看过。手机截屏被系统拒绝时不会绕过 Android 限制。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (enabled) "自动截图已开启" else "自动截图已暂停")
                Switch(checked = enabled, onCheckedChange = { value ->
                    if (coordinator.setEnabled(value)) enabled = value
                })
            }
        }
    }
}


@Composable
fun DeviceUnlockSettingsPanel() {
    val context = LocalContext.current
    val store = remember { LocalUnlockSecretStore(context.applicationContext) }
    var configured by remember { mutableStateOf(store.isConfigured()) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("远程解锁", style = MaterialTheme.typography.titleMedium)
            Text(
                if (configured)
                    "已配置本机解锁 PIN。密码只保存在这台手机的 Android Keystore 加密存储里，不上传 Runtime，也不写入 GitHub。"
                else
                    "未配置。保存一次后，我可以把“唤醒 → 打开密码键盘 → 输入 → 验证解锁”作为一个本机原子动作完成。"
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { value ->
                    pin = value.filter { it.isDigit() }.take(16)
                    message = ""
                },
                label = { Text("设备 PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val result = runCatching { store.savePin(pin) }
                    if (result.isSuccess) {
                        configured = true
                        pin = ""
                        message = "已只保存在本机。"
                    } else {
                        message = "PIN 需要是 4–16 位数字。"
                    }
                }) { Text("保存到本机") }
                if (configured) {
                    Button(onClick = {
                        store.clear()
                        configured = false
                        pin = ""
                        message = "本机解锁 PIN 已清除。"
                    }) { Text("清除") }
                }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
