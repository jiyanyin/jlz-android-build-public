package dev.jlz.ibuilab

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Isolated, offline-only UI feasibility lab for InternalBeyond-Mobile.
 *
 * IMPORTANT: No Android bridge, device privileges, API configuration,
 * production storage, phone-state access or native app-launching hooks.
 * No changes to the installed World Between launcher/runtime.
 */
class IbUiLabActivity : Activity() {
    private lateinit var webView: WebView
    private var loadStartMs = 0L

    private val assetOrigin = "appassets.androidplatform.net"
    private val startUrl = "https://$assetOrigin/ib/index.html"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(238, 243, 251)
        window.navigationBarColor = Color.rgb(238, 243, 251)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(assetOrigin)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(238, 243, 251))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
            }

            webViewClient = object : WebViewClient() {
                private fun local(url: android.net.Uri): Boolean =
                    url.scheme == "https" &&
                    url.host == assetOrigin &&
                    url.path?.startsWith("/ib/") == true

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = !local(request.url)

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (!local(request.url)) return emptyResponse()
                    return loader.shouldInterceptRequest(request.url) ?: emptyResponse()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    val elapsedMs = SystemClock.elapsedRealtime() - loadStartMs
                    val script = """(function() {
                        const nav = performance.getEntriesByType('navigation')[0];
                        const paints = performance.getEntriesByType('paint');
                        return JSON.stringify({
                            title: document.title,
                            domLoadedMs: nav ? Math.round(nav.domContentLoadedEventEnd) : null,
                            loadMs: nav ? Math.round(nav.loadEventEnd) : null,
                            paints: paints.map(p => ({name:p.name, ms:Math.round(p.startTime)})),
                            viewportWidth: innerWidth,
                            viewportHeight: innerHeight,
                            documentHeight: document.documentElement.scrollHeight,
                            fontStatus: document.fonts ? document.fonts.status : 'unsupported'
                        });
                    })()"""
                    view.evaluateJavascript(script) { result ->
                        Log.i("IB_UI_LAB", "pageFinishedElapsedMs=$elapsedMs perf=$result")
                    }
                }
            }
        }
        setContentView(webView)
        loadStartMs = SystemClock.elapsedRealtime()
        webView.loadUrl(startUrl)
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
