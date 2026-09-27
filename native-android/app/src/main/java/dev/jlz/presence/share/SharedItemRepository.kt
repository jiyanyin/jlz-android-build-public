package dev.jlz.presence.share

import android.content.Context
import android.net.Uri
import dev.jlz.presence.data.LocalLifeStore
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Durable inbound share / quick-capture store.
 *
 * Text is kept in the local Timeline; shared images are copied into internal
 * storage (no permission prompts, survives the share intent) and only the local
 * path + real source package are recorded — source attribution is never faked.
 */
class SharedItemRepository(private val context: Context) {
    private val app = context.applicationContext
    private val life = LocalLifeStore(app)

    private fun meta(sourcePackage: String?, extra: JSONObject.() -> Unit = {}): String =
        JSONObject().put("source_package", sourcePackage ?: "").apply(extra).toString()

    fun recordTextShare(text: String, sourcePackage: String?) {
        life.recordTimeline(
            "share_text", "你给我看了一条内容", text.take(1000),
            metadataJson = meta(sourcePackage) { put("kind", "share_text") }
        )
    }

    fun recordQuickNote(text: String) {
        life.recordTimeline(
            "quick_note", "你留了一句话", text.take(1000),
            metadataJson = meta(null) { put("kind", "quick_note") }
        )
    }

    /** Copies the shared image into internal storage; returns the path or null. */
    fun recordImageShare(uri: Uri, mime: String?, sourcePackage: String?): String? {
        val dir = File(app.filesDir, "share_inbox").apply { mkdirs() }
        val ext = when (mime?.substringAfterLast('/')?.lowercase()) {
            "jpeg", "jpg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            else -> "img"
        }
        val file = File(dir, "share_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext")
        val copied = runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
        }.isSuccess
        val path = if (copied && file.exists() && file.length() > 0) file.absolutePath else null
        life.recordTimeline(
            "share_image", "你给我看了一张图",
            path?.let { "已保存到本地" } ?: "图片保存失败",
            metadataJson = meta(sourcePackage) {
                put("kind", "share_image")
                put("mime", mime ?: "")
                put("local_path", path ?: "")
            }
        )
        return path
    }
}
