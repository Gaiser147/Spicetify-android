package app.spicetify.android.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connects the WebView (which actually plays audio) with the media session.
 * The activity registers a [CommandSink] that evaluates bridge commands in the
 * page; the bridge reports state through [onStateJson].
 */
object PlaybackController {
    fun interface CommandSink {
        fun send(command: String, argument: String?)
    }

    /** Asynchronous request to the page (media library browse/search). */
    fun interface Requester {
        /** Returns false when no page is available; [onResult] gets the JSON reply. */
        fun request(method: String, argument: String, onResult: (String) -> Unit): Boolean
    }

    private val mutableState = MutableStateFlow(WebPlaybackState())
    val state: StateFlow<WebPlaybackState> = mutableState.asStateFlow()

    @Volatile
    var sink: CommandSink? = null

    @Volatile
    var requester: Requester? = null

    /** True while the WebView with the web player exists. */
    val isPlayerAvailable: Boolean get() = sink != null

    fun onStateJson(json: String) {
        runCatching { WebPlaybackState.fromJson(json, SystemClock.elapsedRealtime()) }
            .onSuccess { mutableState.value = it }
    }

    fun command(name: String, argument: String? = null) {
        sink?.send(name, argument)
    }

    fun play() = command("play")
    fun pause() = command("pause")
    fun next() = command("next")
    fun previous() = command("previous")
    fun seek(positionMs: Long) = command("seek", positionMs.toString())
    fun toggleShuffle() = command("toggleShuffle")
    fun toggleRepeat() = command("toggleRepeat")
    fun toggleLike() = command("toggleHeart")
    fun navigate(uriOrUrl: String) = command("navigate", uriOrUrl)
    fun playItem(itemId: String) = command("playItem", itemId)
    fun playSearch(query: String) = command("playSearch", query)
}
