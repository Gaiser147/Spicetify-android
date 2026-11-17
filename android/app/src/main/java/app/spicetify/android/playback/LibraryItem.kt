package app.spicetify.android.playback

import org.json.JSONObject

/** An entry of the media library tree shown in Android Auto. */
data class LibraryItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val artUrl: String = "",
    val playable: Boolean = false,
    val browsable: Boolean = false,
) {
    companion object {
        /** Parses {"ok": true, "items": [...]} from bridge.js; null on error. */
        fun parseResult(json: String): List<LibraryItem>? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.optBoolean("ok")) return null
            val arr = o.optJSONArray("items") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = it.optString("id")
                if (id.isEmpty()) return@mapNotNull null
                LibraryItem(
                    id = id,
                    title = it.optString("title"),
                    subtitle = it.optString("subtitle"),
                    artUrl = it.optString("artUrl"),
                    playable = it.optBoolean("playable"),
                    browsable = it.optBoolean("browsable"),
                )
            }
        }
    }
}
