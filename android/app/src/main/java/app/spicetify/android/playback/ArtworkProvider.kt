package app.spicetify.android.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.spicetify.android.BuildConfig
import app.spicetify.android.engine.EngineManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/**
 * Serves cover art to Android Auto, which only loads content:// URIs for
 * browse items. Images are downloaded from Spotify's image CDNs and cached.
 * Only those hosts are allowed, so the provider cannot be used as a proxy.
 */
class ArtworkProvider : ContentProvider() {
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }
    private val dir by lazy { File(context!!.cacheDir, "artwork").apply { mkdirs() } }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val source = uri.getQueryParameter("u") ?: throw FileNotFoundException("missing url")
        if (!isAllowed(source)) throw FileNotFoundException("host not allowed")
        val file = File(dir, EngineManager.sha1(source))
        if (!file.isFile) {
            val tmp = File(dir, file.name + ".tmp")
            http.newCall(Request.Builder().url(source).build()).execute().use { res ->
                if (!res.isSuccessful) throw FileNotFoundException("HTTP ${res.code}")
                tmp.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
            }
            tmp.renameTo(file)
            trim()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > MAX_FILES) files.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val MAX_FILES = 300
        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.artwork"
        private val ALLOWED_SUFFIXES = listOf(".scdn.co", ".spotifycdn.com")

        fun isAllowed(url: String): Boolean {
            val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val host = parsed.host ?: return false
            return parsed.scheme == "https" && ALLOWED_SUFFIXES.any { host.endsWith(it) }
        }

        /** content:// URI for an https artwork URL, or null if not allowed. */
        fun contentUri(url: String): Uri? {
            if (url.isEmpty() || !isAllowed(url)) return null
            return Uri.Builder().scheme("content").authority(AUTHORITY).path("art").appendQueryParameter("u", url).build()
        }
    }
}
