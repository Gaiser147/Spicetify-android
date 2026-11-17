package app.spicetify.android.engine

import app.spicetify.android.BuildConfig
import app.spicetify.android.data.ConfigRepository
import app.spicetify.android.data.SpicetifyConfig
import app.spicetify.android.data.SpicetifyFiles
import app.spicetify.android.spicengine.Engine
import app.spicetify.android.spicengine.Spicengine
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A response for a request below /__spicetify/ or a custom app chunk. */
class SpicetifyResource(val mimeType: String, val data: ByteArray)

/**
 * Owns the Go patch engine for the current [SpicetifyConfig] and serves the
 * Spicetify files the web player requests.
 */
class EngineManager(
    private val files: SpicetifyFiles,
    private val configRepository: ConfigRepository,
) {
    private class State(val config: SpicetifyConfig, val engine: Engine, val key: String)

    @Volatile
    private var state: State? = null

    /** Changes whenever patch output may change (engine version + config). */
    val cacheKey: String get() = current().key

    /** Safe mode: the web player runs without any Spicetify changes. */
    val safeMode: Boolean get() = configRepository.current.safeMode

    private fun current(): State {
        val config = configRepository.current
        state?.let { if (it.config == config) return it }
        synchronized(this) {
            state?.let { if (it.config == config) return it }
            val engineConfig = engineConfigJson(config)
            // Desktop and web player share their class name hashes, so css-map.json applies.
            val cssMap = files.readText("css-map.json") ?: ""
            val engine = Spicengine.newEngine(engineConfig, cssMap)
            val key = sha1(Spicengine.version() + "\n" + engineConfig + "\n" + cssMap.length + "\n" + config.safeMode)
            return State(config, engine, key).also { state = it }
        }
    }

    fun engineConfigJson(config: SpicetifyConfig): String {
        val apps = config.customApps.filter { files.exists("CustomApps/$it/index.js") }
        val appExtensions = JSONObject()
        for (app in apps) {
            val manifest = files.readText("CustomApps/$app/manifest.json") ?: continue
            val list = JSONArray(Spicengine.customAppExtensionFiles(manifest))
            if (list.length() > 0) appExtensions.put(app, list)
        }
        val themeJs = config.injectThemeJs && files.exists("Themes/${config.currentTheme}/theme.js")
        return JSONObject().apply {
            put("spicetifyVersion", "android-${BuildConfig.VERSION_NAME}")
            put("currentTheme", config.currentTheme)
            put("colorScheme", config.colorScheme)
            put("injectThemeJs", themeJs)
            put("exposeApis", config.exposeApis)
            put("disableSentry", config.disableSentry)
            put("disableLogging", config.disableLogging)
            put("removeRtl", config.removeRtl)
            put("sidebarConfig", config.sidebarConfig)
            put("homeConfig", config.homeConfig)
            put("expFeatures", config.expFeatures)
            put("extensions", JSONArray(config.extensions.filter { files.exists("Extensions/$it") }))
            put("customApps", JSONArray(apps))
            put("customAppExtensions", appExtensions)
            put("extraScripts", JSONArray(listOf("android/bridge.js", "android/mobile.js")))
            put("extraStyles", JSONArray(listOf("android/mobile.css")))
            put("basePath", BASE_PATH)
        }.toString()
    }

    fun documentStartScript(): String {
        val layout = configRepository.current.layoutMode
        // mobile.js reads the forced layout from localStorage.
        val layoutInit = "try{localStorage.setItem('spicetify-android:layout','${layout.value}')}catch(e){}"
        return layoutInit + "\n" + current().engine.documentStartScript()
    }

    fun patchAsset(url: String, content: String): String = current().engine.patchAsset(url, content)

    fun report(): String = current().engine.report()

    /** Serves a path below /__spicetify/ (without the prefix). */
    fun resolve(rel: String): SpicetifyResource? {
        if (rel == "boot.js") return SpicetifyResource("text/javascript", documentStartScript().toByteArray())
        val s = current()
        val candidates = JSONArray(s.engine.resolveSpicetifyPath(rel))
        val mime = Spicengine.contentType(rel)
        for (i in 0 until candidates.length()) {
            val candidate = candidates.getString(i)
            val data = when (candidate) {
                "generated:colors.css" -> colorsCss(s.config).toByteArray()
                "generated:user.css" -> userCss(s.config).toByteArray()
                else -> files.readBytes(candidate)
            } ?: continue
            return SpicetifyResource(mime, data)
        }
        return null
    }

    fun colorsCss(config: SpicetifyConfig): String {
        if (!config.replaceColors) return ""
        val ini = files.readText("Themes/${config.currentTheme}/color.ini") ?: ""
        return Spicengine.colorCSS(ini, config.colorScheme)
    }

    fun userCss(config: SpicetifyConfig): String {
        val themeCss = if (config.injectCss) files.readText("Themes/${config.currentTheme}/user.css") ?: "" else ""
        val snippets = JSONArray(config.snippets.filter { it.enabled }.map { it.css })
        return Spicengine.userCSS(themeCss, snippets.toString())
    }

    /** A custom app's manifest.json ("{}" if it has none). */
    fun customAppManifest(app: String): ByteArray =
        files.readBytes("CustomApps/$app/manifest.json") ?: "{}".toByteArray()

    /** Builds the spicetify-routes-<app> chunk (js) or its stylesheet (css). */
    fun customAppAsset(app: String, ext: String): SpicetifyResource? {
        val dir = "CustomApps/$app/"
        if (ext == "css") {
            return SpicetifyResource("text/css", files.readBytes(dir + "style.css") ?: ByteArray(0))
        }
        val index = files.readText(dir + "index.js") ?: return null
        val manifest = files.readText(dir + "manifest.json") ?: "{}"
        val js = StringBuilder(index)
        val subfiles = JSONArray(Spicengine.customAppSubfiles(manifest))
        for (i in 0 until subfiles.length()) {
            files.readText(dir + subfiles.getString(i))?.let { js.append('\n').append(it) }
        }
        return SpicetifyResource("text/javascript", Spicengine.customAppChunk(app, js.toString()).toByteArray())
    }

    companion object {
        const val BASE_PATH = "/__spicetify/"

        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    }
}
