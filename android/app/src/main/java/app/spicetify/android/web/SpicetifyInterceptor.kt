package app.spicetify.android.web

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.spicetify.android.diagnostics.DiagnosticsLog
import app.spicetify.android.engine.EngineManager
import app.spicetify.android.engine.SpicetifyResource
import app.spicetify.android.spicengine.Spicengine
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles the WebView requests that Spicetify needs to change:
 *  - web player JS/CSS bundles from the CDN are downloaded, patched and cached,
 *  - custom app chunks (spicetify-routes-<app>) are generated,
 *  - /__spicetify/... on open.spotify.com serves Spicetify's files,
 *  - optionally the main document, to inject the boot script on WebViews
 *    without DOCUMENT_START_SCRIPT support.
 * Everything else goes to the network untouched.
 */
class SpicetifyInterceptor(
    private val engine: EngineManager,
    private val cache: PatchCache,
    private val http: OkHttpClient,
    private val userAgent: String,
) {
    /** Set when the WebView cannot run document start scripts. */
    @Volatile
    var injectIntoDocument = false

    private val locks = ConcurrentHashMap<String, Any>()

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET") return null
        if (engine.safeMode) return null
        val url = request.url
        return try {
            when {
                url.host == CDN_HOST && url.path?.startsWith(CDN_PATH) == true -> cdnAsset(request)
                url.host == WEB_HOST && url.path?.startsWith(EngineManager.BASE_PATH) == true -> spicetifyFile(url)
                injectIntoDocument && request.isForMainFrame && url.host == WEB_HOST -> document(request)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "interception failed for $url", e)
            DiagnosticsLog.log("intercept", "failed for $url: $e")
            null
        }
    }

    private fun cdnAsset(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val app = Spicengine.customAppName(url)
        if (app.isNotEmpty()) {
            val ext = if (request.url.path.orEmpty().endsWith(".css")) "css" else "js"
            val res = engine.customAppAsset(app, ext) ?: return notFound()
            return ok(res, cors = true)
        }
        if (Spicengine.assetKind(url).isEmpty()) return null

        val key = engine.cacheKey
        cache.get(url, key)?.let { return ok(SpicetifyResource(mimeFor(url), it), cors = true) }

        // One download/patch per URL at a time.
        val lock = locks.getOrPut(url) { Any() }
        synchronized(lock) {
            cache.get(url, key)?.let { return ok(SpicetifyResource(mimeFor(url), it), cors = true) }
            val original = fetch(request) ?: return null
            val started = System.nanoTime()
            val patched = engine.patchAsset(url, original).toByteArray()
            DiagnosticsLog.log("patch", "${request.url.lastPathSegment}: ${(System.nanoTime() - started) / 1_000_000} ms")
            cache.put(url, key, patched)
            locks.remove(url)
            return ok(SpicetifyResource(mimeFor(url), patched), cors = true)
        }
    }

    private fun fetch(request: WebResourceRequest): String? {
        val builder = Request.Builder().url(request.url.toString())
        for ((k, v) in request.requestHeaders) {
            if (k.equals("Range", true) || k.equals("Accept-Encoding", true)) continue
            builder.header(k, v)
        }
        builder.header("User-Agent", userAgent)
        http.newCall(builder.build()).execute().use { res ->
            if (!res.isSuccessful) {
                DiagnosticsLog.log("patch", "upstream HTTP ${res.code} for ${request.url}")
                return null
            }
            return res.body?.string()
        }
    }

    private fun spicetifyFile(url: Uri): WebResourceResponse {
        val rel = url.path.orEmpty().removePrefix(EngineManager.BASE_PATH)
        val res = engine.resolve(Uri.decode(rel))
        if (res == null) {
            DiagnosticsLog.log("files", "404 ${EngineManager.BASE_PATH}$rel")
            return notFound()
        }
        return ok(res, cors = false, cache = false)
    }

    /** Fallback: fetch the HTML ourselves and add the boot script to <head>. */
    private fun document(request: WebResourceRequest): WebResourceResponse? {
        val accept = request.requestHeaders["Accept"].orEmpty()
        if (!accept.contains("text/html")) return null
        val url = request.url.toString()
        val cookies = CookieManager.getInstance()
        val builder = Request.Builder().url(url)
        for ((k, v) in request.requestHeaders) {
            if (k.equals("Accept-Encoding", true)) continue
            builder.header(k, v)
        }
        builder.header("User-Agent", userAgent)
        cookies.getCookie(url)?.let { builder.header("Cookie", it) }
        http.newBuilder().followRedirects(false).build().newCall(builder.build()).execute().use { res ->
            storeCookies(url, res.headers)
            if (res.code != 200 || res.body?.contentType()?.subtype != "html") return null
            val html = res.body!!.string()
            val tag = "<script src=\"${EngineManager.BASE_PATH}boot.js\"></script>"
            val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
            val injected = if (head != null) html.replaceRange(head.range.last + 1, head.range.last + 1, tag) else tag + html
            val headers = HashMap<String, String>()
            res.headers["Content-Security-Policy"]?.let { headers["Content-Security-Policy"] = it }
            return WebResourceResponse("text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream(injected.toByteArray()))
        }
    }

    private fun storeCookies(url: String, headers: Headers) {
        val cm = CookieManager.getInstance()
        headers.values("Set-Cookie").forEach { cm.setCookie(url, it) }
    }

    private fun ok(res: SpicetifyResource, cors: Boolean, cache: Boolean = true): WebResourceResponse {
        val headers = HashMap<String, String>()
        if (cors) headers["Access-Control-Allow-Origin"] = "https://$WEB_HOST"
        headers["Cache-Control"] = if (cache) "public, max-age=31536000, immutable" else "no-store"
        val charset = if (res.mimeType.startsWith("text/") || res.mimeType.endsWith("json")) "utf-8" else null
        return WebResourceResponse(res.mimeType, charset, 200, "OK", headers, ByteArrayInputStream(res.data))
    }

    private fun notFound() =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeFor(url: String) = if (Uri.parse(url).path.orEmpty().endsWith(".css")) "text/css" else "text/javascript"

    companion object {
        private const val TAG = "SpicetifyInterceptor"
        const val WEB_HOST = "open.spotify.com"
        const val CDN_HOST = "open.spotifycdn.com"
        const val CDN_PATH = "/cdn/build/web-player/"
    }
}
