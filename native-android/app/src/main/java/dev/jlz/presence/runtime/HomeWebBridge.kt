package dev.jlz.presence.runtime

import android.content.Context
import android.webkit.JavascriptInterface
import dev.jlz.presence.focus.EntertainmentGateRepository
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.usage.ForegroundUsageStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Installed only on the allowlisted WebShell. Never exports native credentials to JS. */
class HomeWebBridge(private val context: Context, private val save: (String)->Unit, private val open: ()->Unit) {
    @JavascriptInterface fun available(): Boolean = runBlocking {
        val s = RuntimeSettingsRepository(context).load()
        s.baseUrl.isNotBlank() && s.token.isNotBlank() && s.bridgeName.startsWith("Home")
    }
    @JavascriptInterface fun request(path: String, method: String, body: String): String = runCatching {
        require(path.startsWith("/api/web/") && !path.contains("..") && !path.contains('#') && !path.contains('\\'))
        require(method=="GET" || method=="POST")
        require(body.toByteArray().size <= 262144)
        val s = runBlocking { RuntimeSettingsRepository(context).load() }
        require(s.bridgeName.startsWith("Home") && BridgeStore.validUrl(s.baseUrl))
        val c = URL(s.baseUrl+path).openConnection() as HttpURLConnection
        try {
            c.requestMethod=method; c.connectTimeout=5000; c.readTimeout=10000; c.instanceFollowRedirects=false
            c.setRequestProperty("X-Web-Token",s.token)
            if(method=="POST") {
                val bytes=body.toByteArray(); c.doOutput=true
                c.setRequestProperty("Content-Type","application/json")
                c.setFixedLengthStreamingMode(bytes.size); c.outputStream.use { it.write(bytes) }
                s.bridge?.recordTransfer(sent=bytes.size)
            }
            val code=c.responseCode
            val bytes=(if(code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: byteArrayOf()
            s.bridge?.recordTransfer(received=bytes.size)
            JSONObject().put("status",code).put("body",bytes.toString(Charsets.UTF_8)).toString()
        } finally { c.disconnect() }
    }.getOrElse { JSONObject().put("status",0).put("body","{}").toString() }

    @JavascriptInterface fun snapshot(): String {
        val timeline=JSONArray()
        LocalLifeStore(context).use { db -> db.listTimelineSince(System.currentTimeMillis()-86400000,100).forEach {
            timeline.put(JSONObject().put("id",it.id).put("type",it.type).put("title",it.title).put("at_ms",it.createdAtMs))
        } }
        val usage=JSONArray()
        ForegroundUsageStore(context).use { db -> db.totalsSince(System.currentTimeMillis()-3600000,12).forEach {
            usage.put(JSONObject().put("package",it.packageName).put("foreground_ms",it.durationMs))
        } }
        val captures=JSONArray()
        val root=java.io.File(context.filesDir,"jlz_capture_outbox_v1")
        root.listFiles()?.filter { it.extension=="json" }?.takeLast(30)?.forEach { f ->
            runCatching { val m=JSONObject(f.readText()); captures.put(JSONObject().put("event_id",f.nameWithoutExtension)
                .put("captured_at_ms",m.optLong("captured_at_ms")).put("source_package",m.optString("origin_package"))) }
        }
        return JSONObject().put("device",if(context.resources.configuration.smallestScreenWidthDp>=600) "tablet" else "phone")
            .put("timeline",timeline).put("usage_summary",usage).put("capture_metadata",captures)
            .put("gate",JSONObject().put("enabled",EntertainmentGateRepository(context).enabled()))
            .put("bridge",BridgeStore(context).diagnostics()).toString()
    }
    @JavascriptInterface fun setGateEnabled(value: Boolean) { EntertainmentGateRepository(context).setEnabled(value) }
    @JavascriptInterface fun savePack(raw: String) { if(raw.toByteArray().size<=524288) save(raw) }
    @JavascriptInterface fun openPack() { open() }
}
