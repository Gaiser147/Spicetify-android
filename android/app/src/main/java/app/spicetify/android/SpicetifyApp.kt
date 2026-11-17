package app.spicetify.android

import android.app.Application
import android.content.Intent
import android.webkit.WebSettings
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.spicetify.android.ui.ManagerActivity
import app.spicetify.android.data.ConfigRepository
import app.spicetify.android.data.SpicetifyFiles
import app.spicetify.android.engine.EngineManager
import app.spicetify.android.spicengine.Spicengine
import app.spicetify.android.web.PatchCache
import app.spicetify.android.web.SpicetifyInterceptor
import app.spicetify.android.web.UserAgents
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class SpicetifyApp : Application() {
    lateinit var files: SpicetifyFiles
        private set
    lateinit var config: ConfigRepository
        private set
    lateinit var engine: EngineManager
        private set
    lateinit var patchCache: PatchCache
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var interceptor: SpicetifyInterceptor
        private set

    /** Desktop Chrome user agent matching the installed WebView's version. */
    val userAgent: String by lazy {
        val webView = runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()
        if (webView != null) UserAgents.desktop(webView) else Spicengine.desktopUserAgent()
    }

    override fun onCreate() {
        super.onCreate()
        files = SpicetifyFiles(this)
        config = ConfigRepository(this)
        engine = EngineManager(files, config)
        patchCache = PatchCache(File(cacheDir, "spicetify-patched"))
        http = OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "http"), 32L * 1024 * 1024))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        interceptor = SpicetifyInterceptor(engine, patchCache, http, userAgent)
        publishShortcuts()
    }

    /** Long-press shortcut to the settings, reachable even if the player is blank. */
    private fun publishShortcuts() {
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(this, "settings")
                .setShortLabel(getString(R.string.shortcut_settings))
                .setIcon(IconCompat.createWithResource(this, R.drawable.ic_launcher_foreground))
                .setIntent(Intent(this, ManagerActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build()
            ShortcutManagerCompat.setDynamicShortcuts(this, listOf(shortcut))
        }
    }
}

val android.content.Context.spicetify: SpicetifyApp get() = applicationContext as SpicetifyApp
