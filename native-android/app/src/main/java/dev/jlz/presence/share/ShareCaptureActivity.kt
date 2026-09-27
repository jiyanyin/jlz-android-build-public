package dev.jlz.presence.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle

/**
 * System Share Sheet target ("给老公看"). Runs without UI, durably records the
 * shared text or image locally, then finishes.
 */
class ShareCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SharedItemRepository(this)
        val source = callingPackage ?: referrer?.host
        if (Intent.ACTION_SEND == intent?.action) {
            val type = intent.type
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val stream: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            when {
                type?.startsWith("image/") == true && stream != null ->
                    runCatching { repo.recordImageShare(stream, type, source) }
                !text.isNullOrBlank() ->
                    runCatching { repo.recordTextShare(text, source) }
            }
        }
        finish()
    }
}
