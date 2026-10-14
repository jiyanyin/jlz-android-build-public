package dev.jlz.presence.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL

data class BridgeEndpoint(val name: String, val url: String, val token: String,
    val enabled: Boolean = true, val priority: Int = 0)

/** Device-local provisioning. Credentials never belong in source, diagnostics or exports. */
class BridgeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("home_bridge", Context.MODE_PRIVATE)
    fun endpoints(): List<BridgeEndpoint> {
        val a = JSONArray(prefs.getString("endpoints", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o ->
            BridgeEndpoint(o.getString("name"), o.getString("url"), o.getString("token"),
                o.optBoolean("enabled", true), o.optInt("priority", 0))
        } }.filter { it.enabled }.sortedBy { it.priority }
    }
    fun save(rows: List<BridgeEndpoint>) = synchronized(lock) {
        require(rows.size <= 4)
        require(rows.map { it.name }.distinct().size == rows.size)
        rows.forEach { require(validUrl(it.url) && it.token.isNotBlank()) }
        val a = JSONArray()
        rows.forEach { a.put(JSONObject().put("name",it.name).put("url",it.url.trimEnd('/'))
            .put("token",it.token).put("enabled",it.enabled).put("priority",it.priority)) }
        prefs.edit().putString("endpoints",a.toString()).remove("active").putInt("failures",0).commit()
    }
    fun hasHomeEndpoint()=endpoints().any { it.name.startsWith("Home") }
    fun recordCommand(id: String) { prefs.edit().putString("last_command",id.take(100)).putLong("last_command_at",System.currentTimeMillis()).apply() }
    fun select(fallback: RuntimeSettings): RuntimeSettings = synchronized(lock) {
        val rows = endpoints()
        val selected = rows.find { it.name == prefs.getString("active", "") } ?: rows.firstOrNull()
        if (selected == null) fallback.copy(bridge=this)
        else fallback.copy(baseUrl=selected.url, token=selected.token, bridge=this, bridgeName=selected.name)
    }
    fun pollResult(name: String, success: Boolean) = synchronized(lock) {
        if (name.isBlank()) return@synchronized
        val now = System.currentTimeMillis()
        val edit = prefs.edit().putString("health_$name", if(success) "reachable" else "unreachable")
        if (success) {
            edit.putLong("last_success_$name",now).putString("last_error_$name","").putInt("failures",0)
        } else {
            val n = prefs.getInt("failures",0)+1
            edit.putString("last_error_$name","poll_failed").putInt("failures",n)
            if (n >= 3 && now-prefs.getLong("switched_at",0) >= 120_000) {
                val rows = endpoints()
                val current = rows.indexOfFirst { it.name==name }
                val next = if(rows.size>1) rows[(current+1).mod(rows.size)] else null
                if(next != null) edit.putString("active",next.name).putLong("switched_at",now).putInt("failures",0)
            }
        }
        edit.commit()
    }
    /** Only called by the one command loop, between polls. Health probes never claim commands. */
    fun probePreferred(probe: (BridgeEndpoint) -> Boolean = ::healthProbe) {
        val rows = endpoints()
        val active = prefs.getString("active",rows.firstOrNull()?.name) ?: return
        val preferred = rows.takeWhile { it.name != active }
        val now = System.currentTimeMillis()
        if (preferred.isEmpty() || now-prefs.getLong("probe_at",0)<120_000 ||
            now-prefs.getLong("switched_at",0)<120_000) return
        prefs.edit().putLong("probe_at",now).apply()
        // An optional LAN may be unavailable while Home Funnel HTTPS is reachable.
        for (candidate in preferred) {
            val ok = runCatching { probe(candidate) }.getOrDefault(false)
            synchronized(lock) {
                val key = "probe_wins_${candidate.name}"
                val wins = if(ok) prefs.getInt(key,0)+1 else 0
                val edit = prefs.edit().putInt(key,wins).putString("health_${candidate.name}",if(ok) "reachable" else "unreachable")
                if(wins>=2) edit.putString("active",candidate.name).putLong("switched_at",now).putInt("failures",0).putInt(key,0)
                edit.commit()
            }
            if(ok) break
        }
    }
    private fun healthProbe(endpoint: BridgeEndpoint): Boolean {
        val c = URL(apiUrl(endpoint.url,"/health")).openConnection() as HttpURLConnection
        c.connectTimeout=3000; c.readTimeout=3000; c.instanceFollowRedirects=false
        c.setRequestProperty("X-Auth-Token",endpoint.token)
        return try { c.responseCode==200 && JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optBoolean("ok") }
        finally { c.disconnect() }
    }
    fun recordTransfer(sent: Int=0, received: Int=0) = synchronized(lock) {
        prefs.edit().putLong("bytes_sent",prefs.getLong("bytes_sent",0)+sent)
            .putLong("bytes_received",prefs.getLong("bytes_received",0)+received).apply()
    }
    fun diagnostics(): JSONObject {
        val rows = endpoints()
        val a = JSONArray()
        rows.forEach { a.put(JSONObject().put("name",it.name).put("enabled",it.enabled).put("priority",it.priority)
            .put("base_url",it.url).put("health",prefs.getString("health_${it.name}","unknown"))
            .put("last_success",prefs.getLong("last_success_${it.name}",0))
            .put("last_error",prefs.getString("last_error_${it.name}",""))) }
        val active = prefs.getString("active",rows.firstOrNull()?.name ?: "Existing Runtime")
        val uri = rows.find { it.name==active }?.url.orEmpty()
        return JSONObject().put("active",active).put("endpoints",a)
            .put("mode",connectionMode(uri))
            .put("bytes_sent",prefs.getLong("bytes_sent",0)).put("bytes_received",prefs.getLong("bytes_received",0))
            .put("last_sync",rows.maxOfOrNull { prefs.getLong("last_success_${it.name}",0) } ?: 0)
            .put("last_command",prefs.getString("last_command",""))
            .put("last_command_at",prefs.getLong("last_command_at",0))
    }
    companion object {
        private val lock = Any()
        const val RECOMMENDED_HOME_URL = "https://laptop-p23k8ciu.tail9f1176.ts.net/runtime"
        fun apiUrl(base: String, path: String): String {
            require(validUrl(base) && path.startsWith('/') && !path.startsWith("//"))
            return base.trimEnd('/') + path
        }
        fun connectionMode(raw: String): String = runCatching {
            val u=URI(raw)
            when {
                u.scheme=="https" && u.host.orEmpty().endsWith(".ts.net") -> "Funnel HTTPS"
                u.scheme=="https" -> "HTTPS"
                u.scheme=="http" && u.host.orEmpty().split('.').let { it.size==4 && it[0]=="100" && (it[1].toIntOrNull() ?: -1) in 64..127 } -> "Tailscale private (optional)"
                u.scheme=="http" -> "LAN (optional)"
                else -> "Unconfigured"
            }
        }.getOrDefault("Unconfigured")
        fun validUrl(raw: String): Boolean = runCatching {
            val u = URI(raw.trimEnd('/'))
            require(u.rawUserInfo==null && u.rawQuery==null && u.rawFragment==null && u.host!=null)
            require(u.rawPath.orEmpty().isEmpty() || u.scheme=="https" && u.rawPath=="/runtime")
            if (u.scheme=="https") true else {
                val oct = u.host.split('.').map { it.toIntOrNull() ?: -1 }
                u.scheme=="http" && oct.size==4 && oct.all { it in 0..255 } &&
                    (oct[0]==10 || oct[0]==192 && oct[1]==168 || oct[0]==172 && oct[1] in 16..31 || oct[0]==100 && oct[1] in 64..127)
            }
        }.getOrDefault(false)
    }
}
