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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.spicetify.android.SpicetifyApp
import app.spicetify.android.diagnostics.DiagnosticsLog

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
        fun onPageStarted(url: String)
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
    private var defaultMetadata: UserAgentMetadata? = null
    private var loginMode: Boolean? = null
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
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        // Present a consistent desktop browser: the user agent string alone is
        // not enough, because Chromium also sends client hints ("Android
        // WebView", mobile).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            defaultMetadata = runCatching { WebSettingsCompat.getUserAgentMetadata(view.settings) }.getOrNull()
        }
        applyLoginMode(false)
        DiagnosticsLog.log("webview", "user agent: ${app.userAgent}")
        installServiceWorkerClient()

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
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        // Safe mode: no Spicetify, only the (observing) playback diagnostics.
        val source = if (app.engine.safeMode) app.engine.diagnosticsScript() else app.engine.documentStartScript()
        if (source == startScriptSource) return false
        startScript?.remove()
        startScript = if (source.isEmpty()) null else WebViewCompat.addDocumentStartJavaScript(webView, source, setOf(ORIGIN))
        startScriptSource = source
        return true
    }

    /**
     * Login pages run with the WebView's own user agent and client hints; the
     * web player with the desktop ones. Returns true if the mode changed.
     */
    private fun applyLoginMode(login: Boolean): Boolean {
        if (loginMode == login) return false
        loginMode = login
        val settings = webView.settings
        settings.userAgentString = if (login) null else app.userAgent
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching {
                val metadata = if (login) defaultMetadata else desktopUserAgentMetadata()
                if (metadata != null) WebSettingsCompat.setUserAgentMetadata(settings, metadata)
            }.onFailure { DiagnosticsLog.log("webview", "user agent metadata: $it") }
        }
        DiagnosticsLog.log("webview", if (login) "login pages: device user agent" else "web player: desktop user agent")
        return true
    }

    /**
     * Requests of service workers do not pass through the WebViewClient. The
     * web player's worker would cache the original bundles, so its script is
     * blocked and any other worker request goes through the interceptor.
     */
    private fun installServiceWorkerClient() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) {
            return
        }
        runCatching {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    if (SpicetifyInterceptor.isServiceWorkerScript(request.url)) {
                        DiagnosticsLog.log("sw", "blocked ${request.url}")
                        return SpicetifyInterceptor.notFoundResponse()
                    }
                    return app.interceptor.intercept(request)
                }
            })
        }.onFailure { DiagnosticsLog.log("sw", "service worker client: $it") }
    }

    private fun desktopUserAgentMetadata(): UserAgentMetadata {
        val major = UserAgents.chromeMajor(app.userAgent)
        fun brand(name: String, version: String) =
            UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(version).setFullVersion("$version.0.0.0").build()
        return UserAgentMetadata.Builder()
            .setBrandVersionList(listOf(brand("Chromium", major), brand("Google Chrome", major), brand("Not_A Brand", "24")))
            .setFullVersion("$major.0.0.0")
            .setPlatform("Linux")
            .setPlatformVersion("")
            .setModel("")
            .setMobile(false)
            .build()
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
            val login = UserAgents.isLoginHost(uri.host)
            if (login || isInternalHost(uri.host)) {
                // Switching between web player and login pages changes the
                // user agent, which only applies to new requests.
                if (request.isForMainFrame && request.method == "GET" && applyLoginMode(login)) {
                    view.loadUrl(uri.toString())
                    return true
                }
                return false
            }
            if (!app.config.current.externalLinksInBrowser) return false
            callbacks.openExternal(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            DiagnosticsLog.log("page", "started $url${if (app.engine.safeMode) " (safe mode)" else ""}")
            // Navigations that bypassed shouldOverrideUrlLoading (history, POST):
            // fix the mode; the web player must not run with the device agent.
            val host = Uri.parse(url).host
            val login = UserAgents.isLoginHost(host)
            if (applyLoginMode(login) && host == "open.spotify.com") {
                view.post { view.reload() }
                return
            }
            callbacks.onPageStarted(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            DiagnosticsLog.log("page", "finished $url")
            val hits = app.interceptor.hits
            if (hits > 0) {
                app.interceptor.hits = 0
                DiagnosticsLog.log("patch", "$hits patched assets served from cache")
            }
            CookieManager.getInstance().flush()
            callbacks.onPageFinished(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame || isInternalHost(request.url.host)) {
                DiagnosticsLog.log("net", "error ${error.errorCode} ${error.description} for ${request.url}")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            val host = request.url.host.orEmpty()
            // Main frame, patched bundles and Spotify's APIs (playback licence,
            // spclient): those failures explain a broken page or player.
            if (request.isForMainFrame || host == SpicetifyInterceptor.CDN_HOST || host.endsWith(".spotify.com")) {
                DiagnosticsLog.log("net", "HTTP ${response.statusCode} ${request.method} ${request.url}")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "renderer gone (crash=${detail.didCrash()})")
            DiagnosticsLog.log("webview", "renderer gone (crash=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()})")
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
                DiagnosticsLog.log("drm", "granted protected media (Widevine) to $host")
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            } else {
                DiagnosticsLog.log("drm", "denied ${request.resources.joinToString()} for $host")
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
            val level = message.messageLevel()
            if (level == ConsoleMessage.MessageLevel.ERROR || level == ConsoleMessage.MessageLevel.WARNING ||
                message.message().contains("spicetify", true)
            ) {
                val source = message.sourceId().substringAfterLast('/')
                DiagnosticsLog.log("console", "${level.name.lowercase()}: ${message.message()} ($source:${message.lineNumber()})")
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
