package app.spicetify.android.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.spicetify.android.data.SpicetifyFiles
import app.spicetify.android.spicetify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

/** Downloads or reads user-provided extensions, themes and custom apps. */
class Importer(private val context: Context) {
    private val app = context.spicetify

    /** Converts GitHub "blob" page URLs to raw file URLs. */
    fun normalizeUrl(url: String): String {
        val m = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(url.trim())
        return if (m != null) "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}" else url.trim()
    }

    private suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(normalizeUrl(url)).header("User-Agent", "Spicetify-Android").build()
        app.http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            res.body?.bytes() ?: throw IOException("empty response")
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")

    suspend fun extensionFromUrl(url: String): String {
        val bytes = download(url)
        val name = fileName(normalizeUrl(url).substringBefore('?').substringAfterLast('/'), ".js")
        withContext(Dispatchers.IO) { app.files.installFile("Extensions/$name", bytes) }
        return name
    }

    suspend fun extensionFromFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = fileName(displayName(uri) ?: "extension.js", ".js")
        app.files.installFile("Extensions/$name", open(uri).use { it.readBytes() })
        name
    }

    suspend fun themeFromUrl(url: String): String {
        val bytes = download(url)
        return withContext(Dispatchers.IO) {
            app.files.installZip("Themes", "Theme", bytes.inputStream(), "color.ini")
        }
    }

    suspend fun themeFromFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val fallback = (displayName(uri) ?: "Theme").substringBeforeLast('.')
        open(uri).use { app.files.installZip("Themes", fallback, it, "color.ini") }
    }

    suspend fun customAppFromUrl(url: String): String {
        val bytes = download(url)
        return withContext(Dispatchers.IO) {
            app.files.installZip("CustomApps", "custom-app", bytes.inputStream(), "index.js")
        }
    }

    suspend fun customAppFromFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val fallback = (displayName(uri) ?: "custom-app").substringBeforeLast('.')
        open(uri).use { app.files.installZip("CustomApps", fallback, it, "index.js") }
    }

    private fun fileName(raw: String, defaultExt: String): String {
        val base = SpicetifyFiles.sanitizeName(raw.ifEmpty { "extension$defaultExt" })
        return if (base.endsWith(".js") || base.endsWith(".mjs")) base else base + defaultExt
    }
}
