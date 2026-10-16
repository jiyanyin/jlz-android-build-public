package dev.jlz.presence.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat

/** The owner's picture is injected into the authenticated private APK, never public source. */
internal object ConversationAvatars {
    const val OWNER_ASSET = "world-between/owner-avatar.jpg"

    fun owner(context: Context): Person = Person.Builder()
        .setName("你")
        .setKey("world-between-owner")
        .setIcon(IconCompat.createWithBitmap(ownerBitmap(context)))
        .build()

    private fun ownerBitmap(context: Context): Bitmap {
        val size = 128
        val source = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(OWNER_ASSET).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
            context.assets.open(OWNER_ASSET).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull()
        if (source != null) {
            val edge = minOf(source.width, source.height)
            val square = Bitmap.createBitmap(source, (source.width - edge) / 2, (source.height - edge) / 2, edge, edge)
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            if (square !== source && square !== scaled) square.recycle()
            if (source !== scaled) source.recycle()
            return scaled
        }
        // Public/test builds have an owner-only fallback, never the companion's portrait.
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(232, 217, 231)
            canvas.drawCircle(64f, 64f, 64f, paint)
            paint.color = Color.rgb(90, 65, 88)
            paint.textSize = 62f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("音", 64f, 64f - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }
}
