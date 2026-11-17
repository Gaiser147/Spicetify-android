package app.spicetify.android

import android.app.Application
import app.spicetify.android.data.ConfigRepository
import app.spicetify.android.data.SpicetifyFiles
import app.spicetify.android.engine.EngineManager
import app.spicetify.android.spicengine.Spicengine
import app.spicetify.android.web.PatchCache
import app.spicetify.android.web.SpicetifyInterceptor
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

    val userAgent: String by lazy { Spicengine.desktopUserAgent() }

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
    }
}

val android.content.Context.spicetify: SpicetifyApp get() = applicationContext as SpicetifyApp
