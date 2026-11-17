package app.spicetify.android.data

import org.json.JSONArray
import org.json.JSONObject

/** A user CSS snippet, appended to user.css when enabled. */
data class Snippet(val id: String, val name: String, val css: String, val enabled: Boolean = true)

/** Layout of the web player: automatic by screen width, or forced. */
enum class LayoutMode(val value: String) {
    AUTO("auto"),
    MOBILE("mobile"),
    DESKTOP("desktop");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: AUTO
    }
}

/**
 * Android counterpart of config-xpui.ini. Persisted as JSON in
 * filesDir/spicetify/config.json by [ConfigRepository].
 */
data class SpicetifyConfig(
    // [Setting]
    val currentTheme: String = "SpicetifyDefault",
    val colorScheme: String = "",
    val injectCss: Boolean = true,
    val replaceColors: Boolean = true,
    val injectThemeJs: Boolean = true,
    // [AdditionalOptions]
    val extensions: List<String> = emptyList(),
    val customApps: List<String> = listOf("marketplace", "lyrics-plus"),
    val sidebarConfig: Boolean = true,
    val homeConfig: Boolean = true,
    val expFeatures: Boolean = true,
    // [Preprocesses]
    val exposeApis: Boolean = true,
    val disableSentry: Boolean = true,
    val disableLogging: Boolean = true,
    val removeRtl: Boolean = false,
    // Android
    val snippets: List<Snippet> = emptyList(),
    val layoutMode: LayoutMode = LayoutMode.AUTO,
    val webViewDebugging: Boolean = false,
    val externalLinksInBrowser: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("currentTheme", currentTheme)
        put("colorScheme", colorScheme)
        put("injectCss", injectCss)
        put("replaceColors", replaceColors)
        put("injectThemeJs", injectThemeJs)
        put("extensions", JSONArray(extensions))
        put("customApps", JSONArray(customApps))
        put("sidebarConfig", sidebarConfig)
        put("homeConfig", homeConfig)
        put("expFeatures", expFeatures)
        put("exposeApis", exposeApis)
        put("disableSentry", disableSentry)
        put("disableLogging", disableLogging)
        put("removeRtl", removeRtl)
        put("snippets", JSONArray().apply {
            snippets.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("css", it.css).put("enabled", it.enabled))
            }
        })
        put("layoutMode", layoutMode.value)
        put("webViewDebugging", webViewDebugging)
        put("externalLinksInBrowser", externalLinksInBrowser)
    }

    companion object {
        fun fromJson(json: JSONObject): SpicetifyConfig {
            val d = SpicetifyConfig()
            return SpicetifyConfig(
                currentTheme = json.optString("currentTheme", d.currentTheme),
                colorScheme = json.optString("colorScheme", d.colorScheme),
                injectCss = json.optBoolean("injectCss", d.injectCss),
                replaceColors = json.optBoolean("replaceColors", d.replaceColors),
                injectThemeJs = json.optBoolean("injectThemeJs", d.injectThemeJs),
                extensions = json.optStringList("extensions") ?: d.extensions,
                customApps = json.optStringList("customApps") ?: d.customApps,
                sidebarConfig = json.optBoolean("sidebarConfig", d.sidebarConfig),
                homeConfig = json.optBoolean("homeConfig", d.homeConfig),
                expFeatures = json.optBoolean("expFeatures", d.expFeatures),
                exposeApis = json.optBoolean("exposeApis", d.exposeApis),
                disableSentry = json.optBoolean("disableSentry", d.disableSentry),
                disableLogging = json.optBoolean("disableLogging", d.disableLogging),
                removeRtl = json.optBoolean("removeRtl", d.removeRtl),
                snippets = json.optJSONArray("snippets")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let {
                            Snippet(
                                id = it.optString("id"),
                                name = it.optString("name"),
                                css = it.optString("css"),
                                enabled = it.optBoolean("enabled", true),
                            )
                        }
                    }
                } ?: d.snippets,
                layoutMode = LayoutMode.from(json.optString("layoutMode")),
                webViewDebugging = json.optBoolean("webViewDebugging", d.webViewDebugging),
                externalLinksInBrowser = json.optBoolean("externalLinksInBrowser", d.externalLinksInBrowser),
            )
        }

        private fun JSONObject.optStringList(key: String): List<String>? {
            val arr = optJSONArray(key) ?: return null
            return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        }
    }
}
