package dev.jlz.presence.ui.sully

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.jlz.presence.launcher.LauncherRepository
import dev.jlz.presence.navigation.PresenceRoute
import dev.jlz.presence.navigation.PresenceRouteBus
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * NON-PRODUCTION, opt-in SullyOS UI shell. The original JLZ Compose pages,
 * data and services remain the source of truth. Never expose tokens, phone
 * observations, accessibility, or unrestricted intents to embedded JavaScript.
 *
 * Assets are a license-preserving, pinned upstream build made in CI;
 * this repository does not vendor SullyOS or ship any author's credentials.
 */
class SullyPreviewActivity : Activity() {
    private lateinit var webView: WebView
    private val localHost = "appassets.androidplatform.net"
    private val startUrl = "https://appassets.androidplatform.net/sully/index.html"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(255, 244, 250)
        window.navigationBarColor = Color.rgb(207, 230, 248)

        // Local source builds omit the downloaded assets; fail visibly.
        if (runCatching { assets.open("sully/index.html").close() }.isFailure) {
            setContentView(TextView(this).apply {
                text = "糯米机资源尚未打包。请使用 Sully UI Preview 专用 GitHub Actions 构建产物。"
                textSize = 18f
                setPadding(32, 64, 32, 32)
                setBackgroundColor(Color.rgb(255, 244, 250))
            })
            return
        }

        val loader = WebViewAssetLoader.Builder()
            .setDomain(localHost)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(255, 244, 250))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                builtInZoomControls = false
                displayZoomControls = false
            }
            webViewClient = object : WebViewClient() {
                private fun allowed(request: android.net.Uri): Boolean =
                    request.scheme == "https" &&
                    request.host == localHost &&
                    request.path?.startsWith("/sully/") == true

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !allowed(request.url)

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    if (allowed(request.url)) {
                        loader.shouldInterceptRequest(request.url) ?: blocked()
                    } else {
                        blocked()
                    }
            }
        }

        // Unlike addJavascriptInterface, this bridge only exists at the
        // exact local HTTPS origin. Main-frame messages and a fixed whitelist
        // are the entire permitted native API.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                "JLZNative",
                setOf("https://appassets.androidplatform.net"),
                WebViewCompat.WebMessageListener { _, message, origin, isMainFrame, _ ->
                    if (!isMainFrame || origin.toString() != "https://appassets.androidplatform.net") {
                        return@WebMessageListener
                    }
                    val action = runCatching {
                        JSONObject(message.data ?: "").optString("action")
                    }.getOrNull() ?: return@WebMessageListener
                    runOnUiThread { openWhitelistedAction(action) }
                }
            )
        }
        setContentView(webView)
        webView.loadUrl(startUrl)
    }

    private fun openWhitelistedAction(action: String) {
        // Known navigation ONLY: no arbitrary URL, package, command or device
        // controls may be provided by the source's JavaScript.
        val route = when (action) {
            "status_light" -> PresenceRoute.Between("status")
            "between" -> PresenceRoute.Between("moments")
            "time_chain" -> PresenceRoute.TimeChain
            "timeline" -> PresenceRoute.Timeline
            "study" -> PresenceRoute.Study
            "diagnostics" -> PresenceRoute.Diagnostics
            "permissions" -> PresenceRoute.PermissionDoctor
            "echo" -> PresenceRoute.Echo
            else -> null
        }
        if (route != null) {
            PresenceRouteBus.open(route)
            finish()
            return
        }
        if (action == "chatgpt") {
            val launched = runCatching {
                LauncherRepository(this).launchApp("com.openai.chatgpt")
            }.getOrDefault(false)
            if (!launched) Toast.makeText(this, "没有找到 ChatGPT 应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
