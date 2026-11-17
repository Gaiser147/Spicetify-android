package app.spicetify.android.web

import app.spicetify.android.engine.EngineManager
import java.io.File

/**
 * Disk cache of patched web player assets. Bundles are content-hashed, so an
 * entry stays valid as long as the engine/config key is unchanged.
 */
class PatchCache(private val dir: File, private val maxBytes: Long = 96L * 1024 * 1024) {
    init {
        dir.mkdirs()
    }

    private fun file(url: String, key: String) = File(dir, EngineManager.sha1("$key\n$url"))

    fun get(url: String, key: String): ByteArray? {
        val f = file(url, key)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun put(url: String, key: String, data: ByteArray) {
        val f = file(url, key)
        val tmp = File(dir, f.name + ".tmp")
        runCatching {
            tmp.writeBytes(data)
            tmp.renameTo(f)
        }
        trim()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized
    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}
