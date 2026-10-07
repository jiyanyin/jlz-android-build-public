package dev.jlz.presence

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import dev.jlz.presence.launcher.AppHubBridge
import dev.jlz.presence.focus.EntertainmentGateBridge
import dev.jlz.presence.study.StudySessionBridge

class WebShellActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var studyBridge: StudySessionBridge
    private var nativeFallbackOpened = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CookieManager.getInstance().setAcceptCookie(true)

        studyBridge = StudySessionBridge(this)

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(238, 240, 252))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AppHubBridge(this@WebShellActivity), "WorldBetweenAppHub")
            addJavascriptInterface(
                EntertainmentGateBridge(this@WebShellActivity),
                "WorldBetweenGate"
            )
            addJavascriptInterface(studyBridge, "WorldBetweenStudy")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    return handleUri(uri)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val uri = url?.let(Uri::parse) ?: return false
                    return handleUri(uri)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        openNativeFallback()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true &&
                        (errorResponse?.statusCode ?: 0) >= 400
                    ) {
                        openNativeFallback()
                    }
                }
            }
        }

        setContentView(webView)

        if (savedInstanceState == null) {
            webView.loadUrl(resolveWebUrl(intent))
        } else {
            webView.restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nativeFallbackOpened = false
        webView.loadUrl(resolveWebUrl(intent))
    }

    private fun resolveWebUrl(intent: Intent?): String {
        val uri = intent?.data
        if (uri?.scheme == "https" && uri.host in WEB_HOSTS) {
            return rewriteToPrimary(uri)
        }
        return WEB_URL
    }

    private fun rewriteToPrimary(uri: Uri): String {
        val builder = Uri.Builder()
            .scheme("https")
            .authority(WEB_HOST)
            .path(uri.path)
        uri.queryParameterNames.forEach { name ->
            uri.getQueryParameters(name).forEach { value ->
                builder.appendQueryParameter(name, value)
            }
        }
        if (uri.getQueryParameter("shell") == null) {
            builder.appendQueryParameter("shell", "android")
        }
        uri.fragment?.let(builder::fragment)
        return builder.build().toString()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::studyBridge.isInitialized) studyBridge.close()
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }

    private fun handleUri(uri: Uri): Boolean {
        if (uri.scheme == "jlz" && uri.host == "native") {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(uri)
                    .putExtra(MainActivity.EXTRA_RETURN_TO_WEB_SHELL, true)
            )
            return true
        }

        if (uri.scheme == "https" && uri.host in WEB_HOSTS) {
            if (uri.host != WEB_HOST) {
                webView.loadUrl(rewriteToPrimary(uri))
                return true
            }
            return false
        }

        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun openNativeFallback() {
        if (nativeFallbackOpened || isFinishing || isDestroyed) return
        nativeFallbackOpened = true
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_WEB_FALLBACK_REASON, "web_shell_unavailable")
            )
            finish()
        }
    }

    companion object {
        private const val WEB_HOST = "between-worlds-prod.pages.dev"
        private val WEB_HOSTS = setOf(
            WEB_HOST,
            "between-worlds-prod.onrender.com"
        )
        private const val WEB_URL = "https://between-worlds-prod.pages.dev/?shell=android"
        const val EXTRA_WEB_FALLBACK_REASON = "web_fallback_reason"

        fun showEntertainmentGate(
            context: android.content.Context,
            packageName: String,
            reason: String
        ): Boolean = runCatching {
            val uri = Uri.parse("https://between-worlds-prod.pages.dev/")
                .buildUpon()
                .appendQueryParameter("shell", "android")
                .appendQueryParameter("entry", "gate")
                .appendQueryParameter("gate_pkg", packageName)
                .appendQueryParameter("gate_reason", reason)
                .build()
            context.startActivity(
                Intent(context, WebShellActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(uri)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
            true
        }.getOrDefault(false)
    }
}
