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
    // The start of a session (WebView setup, page load, registration) is
    // kept; later lines rotate.
    private const val HEAD_LINES = 200
    private const val MAX_LINES = 1000
    private val head = ArrayList<String>()
    private val lines = ArrayDeque<String>()
    private var dropped = 0
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val line = "${time.format(Date())} [$tag] ${message.take(1000)}"
        Log.i("Spicetify/$tag", message.take(1000))
        synchronized(lines) {
            if (head.size < HEAD_LINES) {
                head.add(line)
                return
            }
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
                dropped++
            }
        }
    }

    fun clear() = synchronized(lines) {
        head.clear()
        lines.clear()
        dropped = 0
    }

    /** Device and app details plus the log, for copying into a bug report. */
    fun report(context: Context): String {
        val webView = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val header = buildString {
            appendLine("Spicetify for Android ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("WebView: ${webView?.packageName ?: "?"} ${webView?.versionName ?: "?"}")
            appendLine("----")
        }
        val body = synchronized(lines) {
            buildString {
                head.forEach { appendLine(it) }
                if (dropped > 0) appendLine("… $dropped lines omitted …")
                append(lines.joinToString("\n"))
            }
        }
        return header + body
    }
}
