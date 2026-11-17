package app.spicetify.android.data

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Spicetify's file layout (see src/patcher/host.go), resolved from two roots:
 * user files in filesDir/spicetify override the files bundled in
 * assets/spicetify.
 *
 *   jsHelper/  Extensions/  CustomApps/<app>/  Themes/<theme>/  android/
 */
class SpicetifyFiles(context: Context) {
    private val assets: AssetManager = context.assets
    val userRoot: File = File(context.filesDir, "spicetify").apply { mkdirs() }

    private val assetListCache = HashMap<String, Array<String>>()

    private fun safe(rel: String): String? {
        val clean = rel.replace('\\', '/').trimStart('/')
        if (clean.isEmpty() || clean.split('/').any { it == ".." }) return null
        return clean
    }

    fun userFile(rel: String): File? = safe(rel)?.let { File(userRoot, it) }

    fun isUserFile(rel: String): Boolean = userFile(rel)?.isFile == true

    fun open(rel: String): InputStream? {
        val clean = safe(rel) ?: return null
        val user = File(userRoot, clean)
        if (user.isFile) return user.inputStream()
        return try {
            assets.open("spicetify/$clean")
        } catch (_: IOException) {
            null
        }
    }

    fun readBytes(rel: String): ByteArray? = open(rel)?.use { it.readBytes() }

    fun readText(rel: String): String? = readBytes(rel)?.toString(Charsets.UTF_8)

    fun exists(rel: String): Boolean = open(rel)?.also { it.close() } != null

    /** Names in a directory of both roots (files and folders). */
    fun list(dir: String): List<String> {
        val clean = safe(dir) ?: return emptyList()
        val bundled = assetListCache.getOrPut(clean) {
            try {
                assets.list("spicetify/$clean") ?: emptyArray()
            } catch (_: IOException) {
                emptyArray()
            }
        }
        val user = File(userRoot, clean).list() ?: emptyArray()
        return (bundled.asList() + user).distinct().sorted()
    }

    fun isBundled(rel: String): Boolean {
        val clean = safe(rel) ?: return false
        val parent = clean.substringBeforeLast('/', "")
        val name = clean.substringAfterLast('/')
        return try {
            assets.list(if (parent.isEmpty()) "spicetify" else "spicetify/$parent")?.contains(name) == true
        } catch (_: IOException) {
            false
        }
    }

    // --- Catalog -------------------------------------------------------------

    data class ExtensionInfo(val file: String, val name: String, val description: String, val bundled: Boolean)

    fun extensions(): List<ExtensionInfo> {
        val meta = HashMap<String, Pair<String, String>>()
        readText("extensions-manifest.json")?.let { text ->
            runCatching {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val file = o.optString("main").substringAfterLast('/')
                    meta[file] = o.optString("name") to o.optString("description")
                }
            }
        }
        return list("Extensions")
            .filter { it.endsWith(".js") || it.endsWith(".mjs") }
            .map { file ->
                val (name, desc) = meta[file] ?: (file.substringBeforeLast('.') to "")
                ExtensionInfo(file, name, desc, isBundled("Extensions/$file"))
            }
    }

    fun themes(): List<String> = list("Themes").filter { exists("Themes/$it/color.ini") || exists("Themes/$it/user.css") }

    fun customApps(): List<String> = list("CustomApps").filter { exists("CustomApps/$it/index.js") }

    // --- Installation ----------------------------------------------------------

    fun installFile(rel: String, bytes: ByteArray) {
        val target = userFile(rel) ?: throw IOException("invalid path: $rel")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    /**
     * Extracts a theme or custom app ZIP into [dir]/<name>. A single top-level
     * folder in the archive is stripped. Returns the installed name.
     */
    fun installZip(dir: String, fallbackName: String, input: InputStream, marker: String): String {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                if (name.split('/').any { it == ".." } || name.startsWith("__MACOSX/")) continue
                entries[name] = zip.readBytes()
            }
        }
        if (entries.isEmpty()) throw IOException("empty archive")
        // Find the folder that contains the marker file (e.g. color.ini / index.js).
        val markerPath = entries.keys.filter { it.substringAfterLast('/') == marker }.minByOrNull { it.count { c -> c == '/' } }
            ?: entries.keys.firstOrNull { it.endsWith("user.css") }
            ?: throw FileNotFoundException("$marker not found in archive")
        val prefix = markerPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val name = sanitizeName(prefix.trimEnd('/').substringAfterLast('/').ifEmpty { fallbackName })
        val target = File(userRoot, "$dir/$name")
        target.deleteRecursively()
        for ((path, bytes) in entries) {
            if (!path.startsWith(prefix)) continue
            val out = File(target, path.removePrefix(prefix))
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        }
        return name
    }

    fun deleteUser(rel: String): Boolean = userFile(rel)?.deleteRecursively() == true

    companion object {
        fun sanitizeName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "imported" }
    }
}
