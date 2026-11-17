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
import android.webkit.CookieManager
import app.spicetify.android.SpicetifyApp
import app.spicetify.android.diagnostics.DiagnosticsLog
import app.spicetify.android.playback.PlaybackController
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
        closeSockets()
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
            "wsOpen" -> openSocket(args.optInt(0), args.optString(1))
            "wsSend" -> sockets[args.optInt(0)]?.socket?.send(args.optString(1))
            "wsClose" -> runCatching {
                sockets[args.optInt(0)]?.socket?.close(args.optInt(1, 1000).coerceIn(1000, 4999), args.optString(2).take(120))
            }
        }
    }

    // --- Dealer WebSocket relay (android/web/sockets.js) --------------------

    private class Relay(val socket: WebSocket, var messages: Int = 0)

    private val sockets = ConcurrentHashMap<Int, Relay>()
    private val socketClient by lazy {
        (webView.context.applicationContext as SpicetifyApp).http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Opens Spotify's dealer WebSocket natively, with the headers of a desktop
     * browser, and relays it to the page. Only dealer hosts are accepted.
     */
    private fun openSocket(id: Int, url: String) {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty()
        if (uri.scheme != "wss" || !DEALER_HOST.matches(host)) {
            emitSocket(id, "close", "1008", "not allowed")
            return
        }
        val app = webView.context.applicationContext as SpicetifyApp
        val request = Request.Builder()
            .url(url.replaceFirst("wss://", "https://"))
            .header("Origin", ORIGIN)
            .header("User-Agent", app.userAgent)
            .apply { CookieManager.getInstance().getCookie("https://$host")?.let { header("Cookie", it) } }
            .build()
        val started = System.currentTimeMillis()
        val socket = socketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DiagnosticsLog.log("dealer", "native connection to $host open (${response.protocol})")
                emitSocket(id, "open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val relay = sockets[id]
                if (relay != null && relay.messages++ < 40 && !text.contains("\"pong\"")) {
                    val uri = runCatching { JSONObject(text).optString("uri") }.getOrDefault("")
                    DiagnosticsLog.log("dealer", "message ${text.length} B ${uri.take(80)}")
                }
                emitSocket(id, "message", text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = emitSocket(id, "message", bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val relay = sockets.remove(id)
                DiagnosticsLog.log("dealer", "native connection closed $code after ${(System.currentTimeMillis() - started) / 1000} s, ${relay?.messages ?: 0} messages")
                emitSocket(id, "close", code.toString(), reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(id)
                DiagnosticsLog.log("dealer", "native connection failed: $t ${response?.code ?: ""}")
                emitSocket(id, "error")
                emitSocket(id, "close", "1006", t.message.orEmpty())
            }
        })
        sockets[id] = Relay(socket)
    }

    private fun emitSocket(id: Int, type: String, data: String? = null, reason: String? = null) {
        val js = "window.SpicetifyAndroidSockets&&SpicetifyAndroidSockets.event($id,${JSONObject.quote(type)}," +
            "${if (data == null) "null" else JSONObject.quote(data)},${if (reason == null) "null" else JSONObject.quote(reason)})"
        main.post { webView.evaluateJavascript(js, null) }
    }

    /** Closes relayed sockets (page navigation, player closed). */
    fun closeSockets() {
        sockets.values.forEach { it.socket.cancel() }
        sockets.clear()
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
        private val DEALER_HOST = Regex("(^|.*[.-])dealer(\\.g2)?\\.spotify\\.com")
        private const val REQUEST_TIMEOUT_MS = 20_000L
    }
}
