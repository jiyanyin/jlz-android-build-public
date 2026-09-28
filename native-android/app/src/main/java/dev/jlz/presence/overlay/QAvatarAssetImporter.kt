package dev.jlz.presence.overlay

import android.content.Context
import android.graphics.Bitmap
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
    private const val BUILTIN_PREFIX = "builtin:"
    private val builtinPacks = listOf(
        "winter_white" to "白金围巾 · 冬日陪伴",
        "purple_coat" to "深紫长风衣 · 夜色纪临洲",
        "wolf_guard" to "狼犬拟人 · 守着你",
        "black_suit" to "黑西装 · 冷脸监管"
    )
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

    private fun builtinName(id: String): String? = id.removePrefix(BUILTIN_PREFIX)
        .takeIf { id.startsWith(BUILTIN_PREFIX) && builtinPacks.any { pack -> pack.first == it } }

    private fun validPack(context: Context, id: String?): Boolean = when {
        id == null -> false
        id == VECTOR_ID -> true
        builtinName(id) != null -> true
        else -> validDirectory(packDir(context, id))
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun activePackId(context: Context): String {
        val selected = preferences(context).getString(ACTIVE, null)
        if (validPack(context, selected)) return selected!!
        if (validDirectory(oldDirectory(context))) return LEGACY_ID
        val imported = library(context).listFiles()?.firstOrNull {
            validDirectory(it) && Regex("[0-9a-f]{32}").matches(it.name)
        }?.name
        return imported ?: BUILTIN_PREFIX + "purple_coat"
    }

    @Synchronized
    fun spriteFile(context: Context, name: String, overrideId: String? = null): File? {
        if (name !in names) return null
        val id = overrideId ?: activePackId(context)
        return packDir(context, id)?.let { File(it, "$name.webp") }
            ?.takeIf { it.isFile }
    }

    /** Load from an imported pack or one of the four bundled owner-provided packs. */
    @Synchronized
    fun loadBitmap(context: Context, name: String, overrideId: String? = null): Bitmap? {
        if (name !in names) return null
        val id = overrideId ?: activePackId(context)
        val fallbacks = actionFallbacks(name)
        builtinName(id)?.let { pack ->
            for (candidate in fallbacks) {
                val bitmap = runCatching {
                    context.assets.open("avatar_packs/$pack/$candidate.webp").use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }.getOrNull()
                if (bitmap != null) return bitmap
            }
            return null
        }
        val dir = packDir(context, id) ?: return null
        for (candidate in fallbacks) {
            val file = File(dir, "$candidate.webp")
            if (file.isFile) BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        return null
    }

    private fun actionFallbacks(name: String): List<String> {
        val sameState = when {
            name.startsWith("sleep_") -> listOf("sleep_hug", "sleep_sitting", "sleep_drowsy")
            name.startsWith("study_") -> listOf("study_watch", "study_arms", "idle_think")
            name.startsWith("react_") -> listOf("react_shy", "react_surprised", "idle_smile")
            name.startsWith("idle_") -> listOf("idle", "idle_crouch", "idle_smile")
            else -> listOf("idle")
        }
        return (listOf(name) + sameState + "idle").distinct()
    }

    @Synchronized
    fun listPacks(context: Context): List<PackInfo> {
        val active = activePackId(context)
        val result = mutableListOf(PackInfo(VECTOR_ID, "基础绘制版", 0, active == VECTOR_ID))
        builtinPacks.forEach { (id, label) ->
            val fullId = BUILTIN_PREFIX + id
            val count = names.count { name ->
                runCatching {
                    context.assets.open("avatar_packs/$id/$name.webp").close(); true
                }.getOrDefault(false)
            }
            result += PackInfo(fullId, label, count, active == fullId)
        }
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
        return result
    }

    @Synchronized
    fun selectPack(context: Context, id: String): Boolean {
        if (!validPack(context, id)) return false
        return preferences(context).edit().putString(ACTIVE, id).commit()
    }

    @Synchronized
    fun selectNextPack(context: Context): PackInfo {
        val packs = listPacks(context).filterNot { it.id == VECTOR_ID }
        val current = activePackId(context)
        val index = packs.indexOfFirst { it.id == current }
        val next = packs[(index + 1 + packs.size) % packs.size]
        check(selectPack(context, next.id)) { "角色套装切换失败" }
        return next.copy(active = true)
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
