package app.spicetify.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Loads and persists [SpicetifyConfig]; observable for the UI. */
class ConfigRepository(context: Context) {
    private val file = File(context.filesDir, "spicetify/config.json")
    private val state = MutableStateFlow(load())

    val config: StateFlow<SpicetifyConfig> = state.asStateFlow()
    val current: SpicetifyConfig get() = state.value

    private fun load(): SpicetifyConfig = try {
        if (file.isFile) SpicetifyConfig.fromJson(JSONObject(file.readText())) else SpicetifyConfig()
    } catch (_: Exception) {
        SpicetifyConfig()
    }

    @Synchronized
    fun update(transform: (SpicetifyConfig) -> SpicetifyConfig) {
        val next = transform(state.value)
        if (next == state.value) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "config.json.tmp")
        tmp.writeText(next.toJson().toString(2))
        tmp.renameTo(file)
        state.value = next
    }
}
