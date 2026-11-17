package app.spicetify.android.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.spicetify.android.SpicetifyApp

/**
 * Creates and configures the WebView that runs the Spotify web player with
 * Spicetify injected.
 */
class SpotifyWebView(
    private val context: Context,
    private val app: SpicetifyApp,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPageFinished(url: String)
        fun onRendererGone()
        fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onHideCustomView()
        fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
        fun openExternal(uri: Uri)
        fun onSpotifyUri(uri: String)
    }

    lateinit var webView: WebView
        private set

    private var startScript: ScriptHandler? = null
    private var startScriptSource: String? = null

    /** True when the boot script is injected with DOCUMENT_START_SCRIPT. */
    val usesDocumentStartScript: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    @SuppressLint("SetJavaScriptEnabled")
    fun create(): WebView {
        WebView.setWebContentsDebuggingEnabled(app.config.current.webViewDebugging)
        val view = WebView(context)
        webView = view
        view.setBackgroundColor(Color.parseColor("#121212"))
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        // Keep the renderer at foreground priority while the app is in the
        // background so playback is not killed.
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = app.userAgent
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }

        app.interceptor.injectIntoDocument = !usesDocumentStartScript
        refreshStartScript()

        view.webViewClient = Client()
        view.webChromeClient = ChromeClient()
        return view
    }

    /** (Re)registers the document start script; returns true if it changed. */
    fun refreshStartScript(): Boolean {
        if (!usesDocumentStartScript) return false
        val source = app.engine.documentStartScript()
        if (source == startScriptSource) return false
        startScript?.remove()
        startScript = WebViewCompat.addDocumentStartJavaScript(webView, source, setOf(ORIGIN))
        startScriptSource = source
        return true
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            app.interceptor.intercept(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            when (uri.scheme) {
                "spotify" -> {
                    callbacks.onSpotifyUri(uri.toString())
                    return true
                }
                "http", "https" -> Unit
                "intent" -> {
                    runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }
                        .getOrNull()?.data?.let { callbacks.openExternal(it) }
                    return true
                }
                else -> {
                    callbacks.openExternal(uri)
                    return true
                }
            }
            if (isInternalHost(uri.host)) return false
            if (!app.config.current.externalLinksInBrowser) return false
            callbacks.openExternal(uri)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            CookieManager.getInstance().flush()
            callbacks.onPageFinished(url)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "renderer gone (crash=${detail.didCrash()})")
            callbacks.onRendererGone()
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            // Widevine (protected media) is required to play Spotify. Only
            // grant it to Spotify itself.
            val host = request.origin?.host
            if (host != null && isInternalHost(host) &&
                request.resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            ) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            } else {
                request.deny()
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = callbacks.onShowCustomView(view, callback)

        override fun onHideCustomView() = callbacks.onHideCustomView()

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = callbacks.onShowFileChooser(filePathCallback, fileChooserParams)

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Popups (e.g. "open in new tab") load in the same WebView.
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(context)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (isInternalHost(uri.host)) webView.loadUrl(uri.toString()) else callbacks.openExternal(uri)
                    v.destroy()
                    return true
                }
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.message().contains("spicetify", true)) {
                Log.d(TAG, "[console] ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            }
            return true
        }
    }

    companion object {
        private const val TAG = "SpotifyWebView"
        const val ORIGIN = "https://open.spotify.com"
        const val HOME = "https://open.spotify.com/"

        private val INTERNAL_SUFFIXES = listOf("spotify.com", "spotifycdn.com", "scdn.co", "spotify.net")

        fun isInternalHost(host: String?): Boolean {
            if (host == null) return false
            return INTERNAL_SUFFIXES.any { host == it || host.endsWith(".$it") }
        }

        /** Converts open.spotify.com links and spotify: URIs to a web player URL. */
        fun toWebUrl(uri: Uri): String? = when {
            uri.scheme == "spotify" -> {
                val parts = uri.schemeSpecificPart.split(':').filter { it.isNotEmpty() }
                if (parts.isEmpty()) HOME else HOME + parts.joinToString("/")
            }
            (uri.scheme == "https" || uri.scheme == "http") && uri.host == "open.spotify.com" ->
                uri.buildUpon().scheme("https").build().toString()
            else -> null
        }

        fun openExternal(context: Context, uri: Uri) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                Log.w(TAG, "no activity for $uri")
            }
        }
    }
}
