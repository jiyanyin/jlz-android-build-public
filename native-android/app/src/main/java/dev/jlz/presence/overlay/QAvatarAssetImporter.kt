package dev.jlz.presence.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** App-private character skin library, preserving the original imported pack. */
object QAvatarAssetImporter {
    private val names = setOf(
        "study_watch", "idle_crouch", "idle", "idle_think", "idle_smile",
        "idle_wave", "idle_arms", "react_shy", "react_angry",
        "react_feisty", "react_surprised", "react_proud", "react_tease",
        "react_disappointed", "react_reach", "study_crouch", "study_read",
        "study_note", "study_think", "study_read_sheet", "study_arms",
        "study_encourage", "study_stand", "sleep_sitting", "sleep_hug",
        "sleep_blanket", "sleep_drowsy", "sleep_rubeye", "sleep_curl",
        "sleep_wave", "sleep_nest"
    )
    private val essential = setOf(
        "idle", "study_watch", "sleep_hug",
        "react_shy", "react_angry", "react_surprised"
    )
    const val LOCAL_DIRECTORY = "jlz_q_avatar_v1"
    private const val LIBRARY_DIRECTORY = "jlz_q_avatar_library_v1"
    private const val PREFS = "jlz_q_avatar_library_preferences"
    private const val ACTIVE = "active_pack_id"
    const val LEGACY_ID = "legacy"
    const val VECTOR_ID = "vector"
    data class ImportResult(val count: Int, val packId: String, val label: String)
    data class PackInfo(
        val id: String, val label: String, val count: Int,
        val active: Boolean, val legacy: Boolean = false
    )

    private fun oldDirectory(context: Context): File =
        File(context.applicationContext.filesDir, LOCAL_DIRECTORY)

    private fun library(context: Context): File =
        File(context.applicationContext.filesDir, LIBRARY_DIRECTORY)

    private fun packDir(context: Context, id: String): File? = when {
        id == LEGACY_ID -> oldDirectory(context)
        Regex("[0-9a-f]{32}").matches(id) -> File(library(context), id)
        else -> null
    }

    private fun validDirectory(dir: File?): Boolean =
        dir?.isDirectory == true && essential.all { File(dir, "$it.webp").isFile }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun builtins(context: Context): List<JSONObject> = runCatching {
        val items = JSONObject(context.assets.open("avatar/packs/index.json").bufferedReader().use { it.readText() }).getJSONArray("packs")
        (0 until items.length()).map { items.getJSONObject(it) }
    }.getOrDefault(emptyList())

    fun builtinSprite(context: Context, id: String, name: String): ByteArray? = runCatching {
        val pack = builtins(context).firstOrNull { it.optString("id") == id } ?: return null
        val map = pack.getJSONObject("assets")
        val path = map.optString(name).ifBlank { map.getString("idle") }
        check(!path.contains("..") && path.startsWith("poses/"))
        val bytes = context.assets.open("avatar/packs/$path").use { it.readBytes() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == pack.getJSONObject("hashes").getString(path))
        bytes
    }.getOrNull()

    @Synchronized
    fun activePackId(context: Context): String {
        val selected = preferences(context).getString(ACTIVE, null)
        if (selected == VECTOR_ID) return VECTOR_ID
        if (builtins(context).any { it.optString("id") == selected }) return selected!!
        if (selected != null && validDirectory(packDir(context, selected))) return selected
        if (validDirectory(oldDirectory(context))) return LEGACY_ID
        return library(context).listFiles()?.firstOrNull {
            validDirectory(it) && Regex("[0-9a-f]{32}").matches(it.name)
        }?.name ?: builtins(context).firstOrNull { it.optString("id") == "builtin-cat_lavender" }?.optString("id") ?: VECTOR_ID
    }

    @Synchronized
    fun spriteFile(context: Context, name: String, overrideId: String? = null): File? {
        if (name !in names) return null
        val id = overrideId ?: activePackId(context)
        return packDir(context, id)?.let { File(it, "$name.webp") }
            ?.takeIf { it.isFile }
    }

    @Synchronized
    fun listPacks(context: Context): List<PackInfo> {
        val active = activePackId(context)
        val result = mutableListOf(PackInfo(VECTOR_ID, "基础绘制版", 0, active == VECTOR_ID))
        val original = oldDirectory(context)
        if (validDirectory(original)) {
            result += PackInfo(LEGACY_ID, "最初导入的 Q 版",
                original.listFiles()?.count { it.isFile && it.extension == "webp" } ?: 0,
                active == LEGACY_ID, legacy = true)
        }
        library(context).listFiles()?.filter {
            validDirectory(it) && Regex("[0-9a-f]{32}").matches(it.name)
        }?.sortedBy { it.lastModified() }?.forEach { dir ->
            val label = runCatching {
                JSONObject(File(dir, "manifest.json").readText()).optString("label")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Q 版套装"
            result += PackInfo(dir.name, label,
                dir.listFiles()?.count { it.isFile && it.extension == "webp" } ?: 0,
                active == dir.name)
        }
        builtins(context).forEach { pack -> result += PackInfo(pack.getString("id"), pack.getString("label"), pack.getJSONObject("assets").length(), active == pack.getString("id")) }
        return result
    }

    @Synchronized
    fun selectPack(context: Context, id: String): Boolean {
        if (id != VECTOR_ID && builtins(context).none { it.optString("id") == id } && !validDirectory(packDir(context, id))) return false
        return preferences(context).edit().putString(ACTIVE, id).commit()
    }

    @Synchronized
    fun importPack(context: Context, zipUri: Uri): ImportResult {
        val root = library(context)
        check(root.isDirectory || root.mkdirs()) { "无法建立本地角色库" }
        val id = UUID.randomUUID().toString().replace("-", "")
        val stage = File(root, ".$id.stage")
        val final = File(root, id)
        val found = mutableSetOf<String>()
        var totalBytes = 0L
        val label = runCatching {
            context.contentResolver.query(zipUri, arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0)
            }
        }.getOrNull()?.removeSuffix(".zip")?.trim()?.take(36)
            ?.takeIf { it.isNotBlank() } ?: "Q 版套装"
        check(stage.mkdirs()) { "无法建立临时目录" }
        try {
            val input = context.contentResolver.openInputStream(zipUri)
                ?: error("无法读取所选角色包")
            input.use { source ->
                ZipInputStream(source).use { zip ->
                    val buffer = ByteArray(8192)
                    var entries = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        check(entries <= 100) { "角色包包含过多文件" }
                        if (entry.isDirectory) { zip.closeEntry(); continue }
                        val item = entry.name.replace('\\', '/')
                        val name = item.substringAfterLast('/').removeSuffix(".webp")
                        val isAvatar = item.contains("/avatar/") &&
                            item.endsWith(".webp") && name in names
                        if (!isAvatar) { zip.closeEntry(); continue }
                        check(found.add(name)) { "重复动作：$name" }
                        val imageFile = File(stage, "$name.webp")
                        var size = 0L
                        FileOutputStream(imageFile).use { output ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                size += n
                                totalBytes += n
                                check(size <= 1_500_000L && totalBytes <= 20_000_000L) {
                                    "角色图包超出大小限制"
                                }
                                output.write(buffer, 0, n)
                            }
                        }
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(imageFile.absolutePath, options)
                        check(options.outWidth in 64..2048 && options.outHeight in 64..2048) {
                            "图片损坏或尺寸异常：$name"
                        }
                        zip.closeEntry()
                    }
                }
            }
            check(found.containsAll(essential)) { "角色包缺少必要动作，请使用完整的正式图包" }
            File(stage, "manifest.json").writeText(
                JSONObject().put("id", id).put("label", label)
                    .put("count", found.size).put("imported_at_ms", System.currentTimeMillis())
                    .toString()
            )
            check(stage.renameTo(final)) { "无法保存新角色套装" }
            check(selectPack(context, id)) { "角色已保存，切换失败，请从角色库手动启用" }
            return ImportResult(found.size, id, label)
        } finally {
            stage.deleteRecursively()
        }
    }
}
