package app.spicetify.android.web

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.spicetify.android.diagnostics.DiagnosticsLog
import app.spicetify.android.playback.PlaybackController
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives messages from android/web/bridge.js and sends commands back.
 *
 * Messages use WebViewCompat.addWebMessageListener, which only accepts
 * messages from open.spotify.com. Old WebViews fall back to a
 * @JavascriptInterface.
 */
class NativeBridge(
    private val webView: WebView,
    private val onOpenManager: () -> Unit,
    private val onNavigation: (String) -> Unit,
    private val onBridgeReady: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val nextRequestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (String) -> Unit>()

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun install() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, NAME, setOf(ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame || sourceOrigin != Uri.parse(ORIGIN)) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                runCatching {
                    val o = JSONObject(data)
                    dispatch(o.getString("method"), o.optJSONArray("args") ?: JSONArray())
                }.onFailure { Log.w(TAG, "bad bridge message", it) }
            }
        } else {
            webView.addJavascriptInterface(LegacyInterface(), NAME)
        }
        PlaybackController.sink = PlaybackController.CommandSink { command, argument -> command(command, argument) }
        PlaybackController.requester = PlaybackController.Requester { method, argument, onResult ->
            request(method, argument, onResult)
            true
        }
    }

    fun uninstall() {
        PlaybackController.sink = null
        PlaybackController.requester = null
        pending.values.forEach { it("""{"ok":false,"error":"player closed"}""") }
        pending.clear()
    }

    /** Calls SpicetifyAndroidBridge.request(); the reply arrives as onResult. */
    fun request(method: String, argument: String, onResult: (String) -> Unit) {
        val id = nextRequestId.getAndIncrement()
        pending[id] = onResult
        val js = "window.SpicetifyAndroidBridge?SpicetifyAndroidBridge.request($id,${JSONObject.quote(method)},${JSONObject.quote(argument)}):false"
        main.post {
            webView.evaluateJavascript(js) { result ->
                if (result != "true") pending.remove(id)?.invoke("""{"ok":false,"error":"bridge not ready"}""")
            }
        }
        // Do not keep callers waiting forever.
        main.postDelayed({ pending.remove(id)?.invoke("""{"ok":false,"error":"timeout"}""") }, REQUEST_TIMEOUT_MS)
    }

    private fun dispatch(method: String, args: JSONArray) {
        when (method) {
            "onPlaybackState" -> PlaybackController.onStateJson(args.optString(0, "{}"))
            "onNavigation" -> main.post { onNavigation(args.optString(0, "/")) }
            "openManager" -> main.post(onOpenManager)
            "onBridgeReady" -> {
                DiagnosticsLog.log("bridge", "ready ${args.optString(0)}")
                main.post(onBridgeReady)
            }
            "onResult" -> pending.remove(args.optInt(0))?.invoke(args.optString(1, "{}"))
            "log" -> DiagnosticsLog.log(args.optString(0, "web").take(24), args.optString(1).take(2000))
        }
    }

    /** Runs SpicetifyAndroidBridge.command(name, arg) in the page. */
    fun command(name: String, argument: String? = null) {
        val quoted = JSONObject.quote(name)
        // Safe mode has no bridge.js; the playback test still reports the environment.
        val js = "window.SpicetifyAndroidBridge?SpicetifyAndroidBridge.command($quoted," +
            "${if (argument == null) "undefined" else JSONObject.quote(argument)}):" +
            "($quoted==='testPlayback'&&window.SpicetifyAndroidDiagnostics&&SpicetifyAndroidDiagnostics.testPlayback())"
        main.post { webView.evaluateJavascript(js, null) }
    }

    /** Asks the page to handle a back press; [result] gets true if it did. */
    fun goBack(result: (Boolean) -> Unit) {
        webView.evaluateJavascript("!!(window.SpicetifyAndroidBridge&&SpicetifyAndroidBridge.goBack())") {
            result(it == "true")
        }
    }

    private inner class LegacyInterface {
        @JavascriptInterface
        fun onPlaybackState(json: String) = dispatch("onPlaybackState", JSONArray().put(json))

        @JavascriptInterface
        fun onNavigation(path: String) = dispatch("onNavigation", JSONArray().put(path))

        @JavascriptInterface
        fun openManager() = dispatch("openManager", JSONArray())

        @JavascriptInterface
        fun onBridgeReady(json: String) = dispatch("onBridgeReady", JSONArray().put(json))

        @JavascriptInterface
        fun onResult(id: Int, json: String) = dispatch("onResult", JSONArray().put(id).put(json))

        @JavascriptInterface
        fun log(tag: String, message: String) = dispatch("log", JSONArray().put(tag).put(message))
    }

    companion object {
        private const val TAG = "SpicetifyBridge"
        const val NAME = "SpicetifyAndroid"
        const val ORIGIN = "https://open.spotify.com"
        private const val REQUEST_TIMEOUT_MS = 20_000L
    }
}
