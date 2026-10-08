package dev.jlz.presence.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.jlz.presence.runtime.*
import kotlinx.coroutines.launch

@Composable
fun BridgePanel() {
    val context = LocalContext.current
    val store = remember { BridgeStore(context) }
    val scope = rememberCoroutineScope()
    var lan by remember { mutableStateOf(store.endpoints().find { it.name=="Home LAN" }?.url.orEmpty()) }
    var home by remember { mutableStateOf(store.endpoints().find { it.name=="Home Node" }?.url ?: BridgeStore.RECOMMENDED_HOME_URL) }
    var standby by remember { mutableStateOf(store.endpoints().find { it.name=="Railway Standby" }?.url.orEmpty()) }
    var custom by remember { mutableStateOf(store.endpoints().find { it.name=="Custom" }?.url.orEmpty()) }
    var homeKey by remember { mutableStateOf("") }
    var standbyKey by remember { mutableStateOf("") }
    var customKey by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var diagnostics by remember { mutableStateOf(store.diagnostics().toString(2)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("连接 / Bridge", style=MaterialTheme.typography.titleMedium)
            Text("优先 Home Node；连续 3 次失败才切备用。留空的端点不启用。密钥留空保留已保存值。")
            Text("推荐 Funnel HTTPS：保留手机现有 VPN，无需安装或运行 Tailscale。")
            OutlinedTextField(home,{home=it},label={Text("Home Node · Funnel HTTPS（推荐）")},singleLine=true)
            TextButton(onClick={home=BridgeStore.RECOMMENDED_HOME_URL}) { Text("使用推荐公网地址") }
            OutlinedTextField(lan,{lan=it},label={Text("高级可选 · Home LAN / 私网（可留空）")},singleLine=true)
            OutlinedTextField(homeKey,{homeKey=it},label={Text("Home Node 密钥")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            OutlinedTextField(standby,{standby=it},label={Text("Railway Standby · HTTPS")},singleLine=true)
            OutlinedTextField(standbyKey,{standbyKey=it},label={Text("Standby 密钥")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            OutlinedTextField(custom,{custom=it},label={Text("Custom")},singleLine=true)
            OutlinedTextField(customKey,{customKey=it},label={Text("Custom 密钥")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            Button(onClick={scope.launch {
                result = runCatching {
                    val old = store.endpoints()
                    val legacy = RuntimeSettingsRepository(context).load()
                    val rows = listOf(Triple("Home Node",home,homeKey),Triple("Home LAN",lan,homeKey),Triple("Railway Standby",standby,standbyKey),Triple("Custom",custom,customKey))
                        .mapIndexedNotNull { index, (name,url,key) -> if(url.isBlank()) null else {
                            val token = key.ifBlank { old.find { it.name==name }?.token ?: if(name=="Home LAN") old.find { it.name=="Home Node" }?.token.orEmpty() else if(url==legacy.baseUrl) legacy.token else "" }
                            BridgeEndpoint(name,url.trim().trimEnd('/'),token,priority=index)
                        } }
                    store.save(rows)
                    homeKey=""; standbyKey=""; customKey=""
                    diagnostics=store.diagnostics().toString(2)
                    "连接配置已保存；本地 Gate 和学习不受影响。"
                }.getOrElse { "请检查地址和对应密钥。HTTPS 或私有 IPv4 HTTP 地址均可。" }
            }}) { Text("保存连接") }
            Button(onClick={diagnostics=store.diagnostics().toString(2)}) { Text("刷新诊断") }
            Text(diagnostics,style=MaterialTheme.typography.bodySmall)
            if(result.isNotBlank()) Text(result)
        }
    }
}
