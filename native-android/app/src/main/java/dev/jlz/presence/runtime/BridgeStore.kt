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
                val next = rows.firstOrNull { it.name != name }
                if(next != null) edit.putString("active",next.name).putLong("switched_at",now).putInt("failures",0)
            }
        }
        edit.commit()
    }
    /** Only called by the one command loop, between polls. Health probes never claim commands. */
    fun probePreferred() {
        val preferred = endpoints().firstOrNull() ?: return
        val now = System.currentTimeMillis()
        if (prefs.getString("active",preferred.name) == preferred.name || now-prefs.getLong("probe_at",0)<120_000 ||
            now-prefs.getLong("switched_at",0)<120_000) return
        prefs.edit().putLong("probe_at",now).apply()
        val ok = runCatching {
            val c = URL(preferred.url+"/health").openConnection() as HttpURLConnection
            c.connectTimeout=3000; c.readTimeout=3000; c.instanceFollowRedirects=false
            try { c.responseCode==200 && JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optBoolean("ok") }
            finally { c.disconnect() }
        }.getOrDefault(false)
        synchronized(lock) {
            val wins = if(ok) prefs.getInt("probe_wins",0)+1 else 0
            val edit = prefs.edit().putInt("probe_wins",wins).putString("health_${preferred.name}",if(ok) "reachable" else "unreachable")
            if(wins>=2) edit.putString("active",preferred.name).putLong("switched_at",now).putInt("failures",0).putInt("probe_wins",0)
            edit.commit()
        }
    }
    fun recordTransfer(sent: Int=0, received: Int=0) = synchronized(lock) {
        prefs.edit().putLong("bytes_sent",prefs.getLong("bytes_sent",0)+sent)
            .putLong("bytes_received",prefs.getLong("bytes_received",0)+received).apply()
    }
    fun diagnostics(): JSONObject {
        val rows = endpoints()
        val a = JSONArray()
        rows.forEach { a.put(JSONObject().put("name",it.name).put("enabled",it.enabled).put("priority",it.priority)
            .put("health",prefs.getString("health_${it.name}","unknown"))
            .put("last_success",prefs.getLong("last_success_${it.name}",0))
            .put("last_error",prefs.getString("last_error_${it.name}",""))) }
        val active = prefs.getString("active",rows.firstOrNull()?.name ?: "Existing Runtime")
        val uri = rows.find { it.name==active }?.url.orEmpty()
        return JSONObject().put("active",active).put("endpoints",a)
            .put("mode",if(uri.contains("100.") || uri.contains(".ts.net")) "Tailscale" else if(uri.startsWith("http:")) "LAN" else "HTTPS")
            .put("bytes_sent",prefs.getLong("bytes_sent",0)).put("bytes_received",prefs.getLong("bytes_received",0))
    }
    companion object {
        private val lock = Any()
        fun validUrl(raw: String): Boolean = runCatching {
            val u = URI(raw.trimEnd('/'))
            require(u.rawUserInfo==null && u.rawQuery==null && u.rawFragment==null && u.host!=null && u.path.orEmpty().isEmpty())
            if (u.scheme=="https") true else {
                val oct = u.host.split('.').map { it.toIntOrNull() ?: -1 }
                u.scheme=="http" && oct.size==4 && oct.all { it in 0..255 } &&
                    (oct[0]==10 || oct[0]==192 && oct[1]==168 || oct[0]==172 && oct[1] in 16..31 || oct[0]==100 && oct[1] in 64..127)
            }
        }.getOrDefault(false)
    }
}
