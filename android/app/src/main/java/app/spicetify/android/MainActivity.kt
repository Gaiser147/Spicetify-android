package app.spicetify.android

import android.Manifest
import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.spicetify.android.diagnostics.DiagnosticsLog
import app.spicetify.android.playback.PlaybackController
import app.spicetify.android.playback.PlaybackService
import app.spicetify.android.ui.ManagerActivity
import app.spicetify.android.web.NativeBridge
import app.spicetify.android.web.SpotifyWebView
import com.google.common.util.concurrent.ListenableFuture

/** Hosts the Spotify web player with Spicetify. */
@UnstableApi
class MainActivity : ComponentActivity(), SpotifyWebView.Callbacks {
    private lateinit var root: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var web: SpotifyWebView
    private lateinit var bridge: NativeBridge
    private var webView: WebView? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageLoaded = false
    private var pendingNavigation: String? = null
    private var pendingSearch: String? = null
    private var engineKeyAtLoad: String? = null
    private var playerReady = false
    private lateinit var recoveryBar: RecoveryBar
    private val watchdog = Runnable { onPlayerTimeout() }

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.parseColor("#121212")) }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        recoveryBar = RecoveryBar(this)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            WindowInsetsCompat.CONSUMED
        }

        createWebView()
        loadInitial(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun createWebView() {
        web = SpotifyWebView(this, spicetify, this)
        val view = web.create()
        webView = view
        bridge = NativeBridge(
            view,
            onOpenManager = { startActivity(Intent(this, ManagerActivity::class.java)) },
            onNavigation = { },
            onBridgeReady = ::onPlayerReady,
        )
        bridge.install()
        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        val margin = (12 * resources.displayMetrics.density).toInt()
        root.addView(
            recoveryBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM)
                .apply { setMargins(margin, margin, margin, margin) },
        )
        WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = false
    }

    private fun loadInitial(intent: Intent?) {
        searchQuery(intent)?.let { pendingSearch = it }
        val target = intent?.data?.let { SpotifyWebView.toWebUrl(it) } ?: SpotifyWebView.HOME
        pageLoaded = false
        engineKeyAtLoad = spicetify.engine.cacheKey
        progress.visibility = View.VISIBLE
        webView?.loadUrl(target)
    }

    /** Query of a MEDIA_PLAY_FROM_SEARCH intent ("play … on Spicetify"). */
    private fun searchQuery(intent: Intent?): String? {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return null
        return intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        searchQuery(intent)?.let { query ->
            if (pageLoaded) PlaybackController.playSearch(query) else pendingSearch = query
            return
        }
        val data = intent.data ?: return
        val url = SpotifyWebView.toWebUrl(data) ?: return
        if (pageLoaded) bridge.command("navigate", url) else pendingNavigation = url
    }

    override fun onStart() {
        super.onStart()
        // Connecting a controller starts the media session service.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed in the manager: re-inject and reload.
        val configChanged = engineKeyAtLoad != null && engineKeyAtLoad != spicetify.engine.cacheKey
        val scriptChanged = web.refreshStartScript()
        WebView.setWebContentsDebuggingEnabled(spicetify.config.current.webViewDebugging)
        if (configChanged || scriptChanged) reloadPlayer()
        updateSafeModeBar()
    }

    private fun reloadPlayer() {
        engineKeyAtLoad = spicetify.engine.cacheKey
        pageLoaded = false
        webView?.reload()
    }

    // --- Recovery ---------------------------------------------------------------

    private fun onPlayerReady() {
        playerReady = true
        root.removeCallbacks(watchdog)
        if (!spicetify.engine.safeMode) recoveryBar.hide()
    }

    private fun onPlayerTimeout() {
        if (playerReady || spicetify.engine.safeMode) return
        DiagnosticsLog.log("watchdog", "player not ready after ${PLAYER_TIMEOUT_MS / 1000} s (url=${webView?.url})")
        recoveryBar.show(
            getString(R.string.recovery_stuck),
            getString(R.string.recovery_reload) to { recoveryBar.hide(); reloadPlayer() },
            getString(R.string.recovery_safe_mode) to { setSafeMode(true) },
            getString(R.string.recovery_diagnostics) to { openDiagnostics() },
        )
    }

    private fun setSafeMode(enabled: Boolean) {
        spicetify.config.update { it.copy(safeMode = enabled) }
        DiagnosticsLog.log("watchdog", "safe mode ${if (enabled) "on" else "off"}")
        web.refreshStartScript()
        recoveryBar.hide()
        updateSafeModeBar()
        reloadPlayer()
    }

    private fun updateSafeModeBar() {
        if (spicetify.engine.safeMode) {
            recoveryBar.show(
                getString(R.string.safe_mode_active),
                getString(R.string.safe_mode_exit) to { setSafeMode(false) },
                getString(R.string.recovery_diagnostics) to { openDiagnostics() },
            )
        } else if (playerReady) {
            recoveryBar.hide()
        }
    }

    private fun openDiagnostics() {
        startActivity(Intent(this, ManagerActivity::class.java).putExtra(ManagerActivity.EXTRA_DIAGNOSTICS, true))
    }

    override fun onStop() {
        super.onStop()
        // Keep the login: cookies are only persisted on flush.
        android.webkit.CookieManager.getInstance().flush()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    override fun onDestroy() {
        root.removeCallbacks(watchdog)
        bridge.uninstall()
        webView?.let {
            root.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun handleBack() {
        if (customView != null) {
            onHideCustomView()
            return
        }
        bridge.goBack { handled ->
            if (handled) return@goBack
            val view = webView
            if (view != null && view.canGoBack() && view.url?.contains("accounts.spotify.com") == true) {
                view.goBack()
            } else {
                // Keep the WebView (and playback) alive instead of finishing.
                moveTaskToBack(true)
            }
        }
    }

    // --- SpotifyWebView.Callbacks ------------------------------------------

    override fun onPageStarted(url: String) {
        bridge.closeSockets()
        if (Uri.parse(url).host != "open.spotify.com") return
        playerReady = false
        root.removeCallbacks(watchdog)
        root.postDelayed(watchdog, PLAYER_TIMEOUT_MS)
    }

    override fun onPageFinished(url: String) {
        progress.visibility = View.GONE
        if (url.startsWith(SpotifyWebView.ORIGIN)) {
            pageLoaded = true
            pendingNavigation?.let {
                pendingNavigation = null
                bridge.command("navigate", it)
            }
            pendingSearch?.let { query ->
                pendingSearch = null
                // Give the player a moment to initialise before searching.
                root.postDelayed({ PlaybackController.playSearch(query) }, 4000)
            }
        }
    }

    override fun onRendererGone() {
        webView?.let {
            root.removeView(it)
            it.destroy()
        }
        createWebView()
        loadInitial(null)
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        customView?.let { onHideCustomView() }
        customView = view
        customViewCallback = callback
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onHideCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    override fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            fileChooser.launch(params.createIntent())
            true
        } catch (_: Exception) {
            fileCallback = null
            false
        }
    }

    override fun openExternal(uri: Uri) = SpotifyWebView.openExternal(this, uri)

    override fun onSpotifyUri(uri: String) = bridge.command("navigate", uri)

    companion object {
        private const val PLAYER_TIMEOUT_MS = 30_000L
    }
}
