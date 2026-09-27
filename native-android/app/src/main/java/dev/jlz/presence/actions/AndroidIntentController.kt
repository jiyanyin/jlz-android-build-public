package dev.jlz.presence.actions

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Uses Android intents/SAF; it never requests broad filesystem access. */
class AndroidIntentController(private val context: Context) {
    fun execute(action: String, payload: JSONObject): Pair<Boolean, String> = runCatching {
        val intent = when (action) {
            "open_uri" -> Intent(Intent.ACTION_VIEW, requiredUri(payload)).apply {
                payload.optString("mime_type").takeIf { it.isNotBlank() }?.let(::setType)
            }
            "share_text" -> Intent(Intent.ACTION_SEND).apply {
                type = payload.optString("mime_type", "text/plain")
                putExtra(Intent.EXTRA_TEXT, payload.optString("text"))
                payload.optString("title").takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            }
            "share_file" -> Intent(Intent.ACTION_SEND).apply {
                type = payload.optString("mime_type", "application/octet-stream")
                val uri = requiredUri(payload)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "shared-file", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            "share_files" -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = payload.optString("mime_type", "application/octet-stream")
                val uris = uriList(payload.optJSONArray("uris"))
                require(uris.isNotEmpty()) { "uris_required" }
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData = ClipData.newUri(context.contentResolver, "shared-files", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            "open_file_picker" -> Intent(context, FilePickerProxyActivity::class.java).apply {
                putExtra(FilePickerProxyActivity.EXTRA_MIME_TYPE, payload.optString("mime_type", "*/*"))
                putExtra(FilePickerProxyActivity.EXTRA_ALLOW_MULTIPLE, payload.optBoolean("allow_multiple", false))
                putExtra(FilePickerProxyActivity.EXTRA_TARGET_PACKAGE, payload.optString("target_package"))
                putExtra(FilePickerProxyActivity.EXTRA_CHOOSER, payload.optBoolean("chooser", false))
                putExtra(FilePickerProxyActivity.EXTRA_CHOOSER_TITLE, payload.optString("chooser_title"))
            }
            else -> error("unsupported_intent_action:$action")
        }
        payload.optString("target_package").takeIf { it.isNotBlank() }?.let(intent::setPackage)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = payload.optBoolean("chooser", false) && action.startsWith("share_")
        context.startActivity(if (chooser) Intent.createChooser(intent, payload.optString("chooser_title")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent)
        true to "intent_started:$action"
    }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }

    private fun requiredUri(payload: JSONObject): Uri {
        val raw = payload.optString("uri").ifBlank { payload.optString("content_uri") }
        require(raw.startsWith("content://") || raw.startsWith("https://") || raw.startsWith("http://")) {
            "content_or_web_uri_required"
        }
        return Uri.parse(raw)
    }

    private fun uriList(array: JSONArray?): List<Uri> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val raw = array.optString(index)
            require(raw.startsWith("content://")) { "content_uri_required_at:$index" }
            add(Uri.parse(raw))
        }
    }
}
