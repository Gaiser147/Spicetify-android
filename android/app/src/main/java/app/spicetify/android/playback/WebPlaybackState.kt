package app.spicetify.android.playback

import org.json.JSONObject

/** Playback state reported by android/web/bridge.js. */
data class WebPlaybackState(
    val hasTrack: Boolean = false,
    val uri: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUrl: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val isPaused: Boolean = true,
    val shuffle: Boolean = false,
    /** 0 = off, 1 = context, 2 = track (Spotify's repeat modes). */
    val repeat: Int = 0,
    val liked: Boolean = false,
    val hasNext: Boolean = true,
    val hasPrevious: Boolean = true,
    /** Elapsed realtime when [positionMs] was measured. */
    val measuredAt: Long = 0,
) {
    companion object {
        fun fromJson(json: String, now: Long): WebPlaybackState {
            val o = JSONObject(json)
            if (!o.optBoolean("hasTrack")) return WebPlaybackState(measuredAt = now)
            return WebPlaybackState(
                hasTrack = true,
                uri = o.optString("uri"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                album = o.optString("album"),
                artUrl = o.optString("artUrl"),
                durationMs = o.optLong("duration"),
                positionMs = o.optLong("position"),
                isPaused = o.optBoolean("isPaused", true),
                shuffle = o.optBoolean("shuffle"),
                repeat = o.optInt("repeat"),
                liked = o.optBoolean("liked"),
                hasNext = o.optBoolean("hasNext", true),
                hasPrevious = o.optBoolean("hasPrevious", true),
                measuredAt = now,
            )
        }
    }
}
