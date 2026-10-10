package dev.jlz.presence.overlay

import android.content.Context
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.File
import android.view.View
import kotlin.random.Random

/**
 * Small self-contained vector prototype: zero downloaded assets, one character
 * across LIFE/STUDY/FOCUS and reaction states. Final illustration pack TBD.
 */
/** Overlay character size, deliberately separate from four-action menu layout. */
object QAvatarScale {
    private const val FILE = "jlz_q_overlay_preferences"
    private const val KEY = "avatar_size_dp"
    const val MIN_DP = 54
    const val MAX_DP = 126
    const val DEFAULT_DP = 78
    const val STEP_DP = 12

    fun get(context: Context): Int =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY, DEFAULT_DP).coerceIn(MIN_DP, MAX_DP)

    fun put(context: Context, requestedDp: Int): Int {
        val value = requestedDp.coerceIn(MIN_DP, MAX_DP)
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY, value).apply()
        return value
    }

    fun percentage(dp: Int): Int = dp * 100 / DEFAULT_DP
}

class ChibiPresenceView(context: Context) : View(context) {
    private var sizeDp: Int = QAvatarScale.get(context)

    fun setSizeDp(valueDp: Int) {
        val adjusted = valueDp.coerceIn(QAvatarScale.MIN_DP, QAvatarScale.MAX_DP)
        if (sizeDp == adjusted) return
        sizeDp = adjusted
        requestLayout()
        invalidate()
    }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mood: String = "watch"
    private val pose = Random.nextInt(3)
    // The source adapter can compile without the separately processed binary
    // asset drop-in. Until the sprites are present in APK assets, use the
    // existing vector. Never invent a completed image integration.
    private val imageCache = object : android.util.LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val missing = mutableSetOf<String>()
    private var loadedPackId: String? = null
    private var previewPackId: String? = null

    /** Preview a pack without changing the user's selected outfit. */
    fun previewArtworkPack(id: String?) {
        if (previewPackId == id) return
        previewPackId = id
        reloadArtwork()
    }
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sleepingPose = listOf("sleep_hug", "sleep_blanket", "sleep_sitting")[Random.nextInt(3)]
    private val studyingPose = listOf("study_watch", "study_crouch", "study_read")[Random.nextInt(3)]

    private fun spriteName(): String = when (mood) {
        "watch", "watching" -> studyingPose
        "break", "wake" -> "sleep_wave"
        "sleepy" -> "sleep_drowsy"
        "offline" -> "idle_think"
        "celebrate" -> "study_encourage"
        "clingy" -> "react_reach"
        "gate", "night" -> "react_angry"
        "sleep" -> sleepingPose
        "shy" -> "react_shy"
        "angry" -> "react_angry"
        "surprised" -> "react_surprised"
        "feisty" -> "react_feisty"
        "tease" -> "react_tease"
        "reach" -> "react_reach"
        "disappointed" -> "react_disappointed"
        "proud" -> "react_proud"
        "idle", "thinking" -> listOf("idle", "idle_crouch", "idle_arms")[pose]
        else -> "idle"
    }

    private fun spriteFor(name: String): Bitmap? {
        val desiredPack = previewPackId ?: QAvatarAssetImporter.activePackId(context)
        if (loadedPackId != desiredPack) {
            imageCache.evictAll(); missing.clear()
            loadedPackId = desiredPack
        }
        imageCache.get(name)?.let { return it }
        if (name !in missing) {
            val decoded = runCatching {
                val imageFile = QAvatarAssetImporter.spriteFile(
                    context, name, previewPackId
                )
                if (imageFile != null) {
                    BitmapFactory.decodeFile(imageFile.absolutePath)
                } else if (desiredPack.startsWith("builtin-")) {
                    QAvatarAssetImporter.builtinSprite(context, desiredPack, name)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } else if (desiredPack == QAvatarAssetImporter.VECTOR_ID) {
                    null
                } else {
                    QAvatarAssetImporter.spriteFile(context, "idle", previewPackId)?.let { BitmapFactory.decodeFile(it.absolutePath) }
                }
            }.getOrNull()
            if (decoded != null) imageCache.put(name, decoded) else missing.add(name)
        }
        return imageCache.get(name)
    }

    /** Called after user imports a replacement pack; the view stays alive. */
    fun reloadArtwork() {
        imageCache.evictAll(); missing.clear()
        loadedPackId = null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reloadArtwork()
    }

    private var reactionUntil = 0L
    fun setMood(value: String) {
        if (System.currentTimeMillis() < reactionUntil || mood == value) return
        mood = value
        invalidate()
    }

    fun react(value: String, after: String = "watch") {
        animate().cancel()
        reactionUntil = System.currentTimeMillis() + 1400L
        mood = value
        val reduce = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val save = context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        if (reduce || save) { postDelayed({ mood = after; invalidate() }, 1400L); invalidate(); return }
        invalidate()
        animate().scaleX(1.1f).scaleY(0.9f).rotation(if (pose % 2 == 0) 8f else -8f)
            .setDuration(170L)
            .withEndAction {
                animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(220L)
                    .withEndAction {
                        postDelayed({
                            mood = after
                            invalidate()
                        }, 500L)
                    }.start()
            }.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = (resources.displayMetrics.density * sizeDp + 0.5f).roundToInt()
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sprite = spriteFor(spriteName())
        if (sprite != null) {
            artworkPaint.isFilterBitmap = true
            canvas.drawBitmap(
                sprite, null,
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                artworkPaint
            )
            return
        }
        val scale = width / 120f
        canvas.save()
        canvas.scale(scale, scale)

        fun oval(color: Int, left: Float, top: Float, right: Float, bottom: Float) {
            pen.color = color
            pen.style = Paint.Style.FILL
            canvas.drawOval(left, top, right, bottom, pen)
        }

        fun rect(color: Int, left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
            pen.color = color
            pen.style = Paint.Style.FILL
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, pen)
        }

        fun line(color: Int, left: Float, top: Float, right: Float, bottom: Float, width: Float = 3f) {
            pen.color = color
            pen.strokeWidth = width
            pen.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(left, top, right, bottom, pen)
        }

        val ink = 0xFF221D2B.toInt()
        val darkHair = 0xFF29232F.toInt()
        val skin = 0xFFFFDCCB.toInt()
        val coat = 0xFF48405A.toInt()
        val purple = 0xFF75629E.toInt()

        // Ambient plate is part of the character, not a full overlay panel.
        oval(0x335C4D78, 20f, 105f, 102f, 116f)
        oval(0xFFEEE8F9.toInt(), 16f, 6f, 107f, 102f)

        // Crouching pose: tucked knees, little shoes, sleeves and short jacket.
        oval(ink, 25f, 94f, 57f, 110f)
        oval(ink, 65f, 94f, 99f, 110f)
        oval(coat, 25f, 71f, 98f, 103f)
        oval(purple, 30f, 83f, 53f, 104f)
        oval(purple, 71f, 83f, 94f, 104f)
        oval(skin, 17f, 84f, 32f, 95f)
        oval(skin, 91f, 83f, 106f, 94f)

        // Hair back, enormous Q-style face, ears and side locks.
        oval(darkHair, 20f, 13f, 104f, 92f)
        oval(skin, 22f, 48f, 35f, 65f)
        oval(skin, 89f, 48f, 103f, 65f)
        oval(skin, 27f, 27f, 98f, 88f)
        oval(darkHair, 25f, 20f, 99f, 53f)
        val fringe = Path().apply {
            moveTo(27f, 39f)
            lineTo(45f, 27f)
            lineTo(43f, 48f)
            lineTo(61f, 31f)
            lineTo(58f, 45f)
            lineTo(78f, 32f)
            lineTo(88f, 44f)
            lineTo(97f, 37f)
            lineTo(93f, 22f)
            lineTo(34f, 17f)
            close()
        }
        pen.color = darkHair
        canvas.drawPath(fringe, pen)
        oval(darkHair, 24f, 38f, 34f, 82f)
        oval(darkHair, 92f, 37f, 102f, 79f)

        // Tiny eyes are stateful, not separate ad hoc icons.
        when (mood) {
            "shy", "sleep" -> {
                line(ink, 43f, 59f, 51f, 61f, 2.8f)
                line(ink, 73f, 61f, 81f, 59f, 2.8f)
            }
            "angry" -> {
                line(ink, 41f, 53f, 51f, 57f, 3.3f)
                line(ink, 74f, 57f, 84f, 53f, 3.3f)
                oval(ink, 45f, 59f, 51f, 67f)
                oval(ink, 74f, 59f, 80f, 67f)
            }
            "surprised" -> {
                oval(ink, 44f, 55f, 52f, 68f)
                oval(ink, 74f, 55f, 82f, 68f)
                oval(0xFFFCFAFF.toInt(), 47f, 56f, 49f, 60f)
                oval(0xFFFCFAFF.toInt(), 77f, 56f, 79f, 60f)
            }
            else -> {
                oval(ink, 45f, 57f, 51f, 66f)
                oval(ink, 75f, 57f, 81f, 66f)
                oval(0xFFFCFAFF.toInt(), 47f, 58f, 49f, 61f)
                oval(0xFFFCFAFF.toInt(), 77f, 58f, 79f, 61f)
            }
        }
        if (mood == "shy" || mood == "surprised") {
            oval(0x99F3A6AE.toInt(), 32f, 66f, 43f, 73f)
            oval(0x99F3A6AE.toInt(), 84f, 66f, 95f, 73f)
        }
        if (mood == "surprised" || mood == "angry") {
            oval(ink, 59f, 71f, 66f, 78f)
        } else {
            line(ink, 60f, 72f, 65f, 73f, 2f)
        }
        // Tiny highlight helps distinguish this from the old text pill.
        oval(0xFFFFFBFA.toInt(), 51f, 40f, 54f, 43f)
        canvas.restore()
    }
}
