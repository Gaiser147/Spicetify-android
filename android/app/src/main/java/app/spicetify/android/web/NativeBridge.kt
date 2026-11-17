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
import app.spicetify.android.playback.PlaybackController
import org.json.JSONArray
import org.json.JSONObject

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
) {
    private val main = Handler(Looper.getMainLooper())

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
    }

    fun uninstall() {
        if (PlaybackController.sink != null) PlaybackController.sink = null
    }

    private fun dispatch(method: String, args: JSONArray) {
        when (method) {
            "onPlaybackState" -> PlaybackController.onStateJson(args.optString(0, "{}"))
            "onNavigation" -> main.post { onNavigation(args.optString(0, "/")) }
            "openManager" -> main.post(onOpenManager)
            "onBridgeReady" -> Log.i(TAG, "bridge ready ${args.optString(0)}")
        }
    }

    /** Runs SpicetifyAndroidBridge.command(name, arg) in the page. */
    fun command(name: String, argument: String? = null) {
        val js = "window.SpicetifyAndroidBridge&&SpicetifyAndroidBridge.command(${JSONObject.quote(name)}," +
            "${if (argument == null) "undefined" else JSONObject.quote(argument)})"
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
    }

    companion object {
        private const val TAG = "SpicetifyBridge"
        const val NAME = "SpicetifyAndroid"
        const val ORIGIN = "https://open.spotify.com"
    }
}
