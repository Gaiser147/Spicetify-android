package app.spicetify.android.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.webkit.WebViewCompat
import app.spicetify.android.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory log of what happens in the player (navigations, console errors,
 * renderer crashes, patching), so problems on a device can be reported from
 * the app (Spicetify settings → Diagnostics) without adb.
 */
object DiagnosticsLog {
    private const val MAX_LINES = 800
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val line = "${time.format(Date())} [$tag] ${message.take(1000)}"
        Log.i("Spicetify/$tag", message.take(1000))
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun clear() = synchronized(lines) { lines.clear() }

    /** Device and app details plus the log, for copying into a bug report. */
    fun report(context: Context): String {
        val webView = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val header = buildString {
            appendLine("Spicetify for Android ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("WebView: ${webView?.packageName ?: "?"} ${webView?.versionName ?: "?"}")
            appendLine("----")
        }
        val body = synchronized(lines) { lines.joinToString("\n") }
        return header + body
    }
}
