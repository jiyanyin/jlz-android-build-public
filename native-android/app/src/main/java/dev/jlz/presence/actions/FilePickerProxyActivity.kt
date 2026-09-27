package dev.jlz.presence.actions

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** SAF picker bridge. The user/system picker grants only the selected URI(s). */
class FilePickerProxyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                type = intent.getStringExtra(EXTRA_MIME_TYPE)?.takeIf { it.isNotBlank() } ?: "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, intent.getBooleanExtra(EXTRA_ALLOW_MULTIPLE, false))
            }, REQUEST_PICK)
        }
    }

    @Deprecated("Android activity-result compatibility for the existing minSdk")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) { finish(); return }
        val uris = buildList {
            data.data?.let { add(it) }
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
        }.distinct()
        if (uris.isEmpty()) { finish(); return }
        uris.forEach { uri -> runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } }
        val share = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = intent.getStringExtra(EXTRA_MIME_TYPE)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newUri(contentResolver, "selected-files", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            intent.getStringExtra(EXTRA_TARGET_PACKAGE)?.takeIf { it.isNotBlank() }?.let(::setPackage)
        }
        startActivity(if (intent.getBooleanExtra(EXTRA_CHOOSER, false))
            Intent.createChooser(share, intent.getStringExtra(EXTRA_CHOOSER_TITLE)) else share)
        finish()
    }

    companion object {
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_ALLOW_MULTIPLE = "allow_multiple"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_CHOOSER = "chooser"
        const val EXTRA_CHOOSER_TITLE = "chooser_title"
        private const val REQUEST_PICK = 4107
    }
}
