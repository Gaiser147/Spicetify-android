package app.spicetify.android.playback

import android.os.Looper
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A Media3 [Player] that mirrors the Spotify web player running in the WebView
 * and forwards transport commands to it. Media3 builds the media notification,
 * lock screen controls, Bluetooth/headset button handling and (later) the
 * Android Auto session on top of it.
 *
 * The web player only exposes the current track, so the playlist is
 * [previous placeholder, current, next placeholder]. That way "skip to
 * next/previous" are always offered and arrive here as seeks to index 2/0.
 */
@UnstableApi
class WebPlayer(looper: Looper = Looper.getMainLooper()) : SimpleBasePlayer(looper) {

    @Volatile
    private var web: WebPlaybackState = WebPlaybackState()

    /** Called on the main thread whenever the bridge reports a new state. */
    fun update(state: WebPlaybackState) {
        web = state
        invalidateState()
    }

    override fun getState(): State {
        val s = web
        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_METADATA,
                COMMAND_GET_TIMELINE,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_PREPARE,
                // Android Auto / voice: selecting an item sets it as the playlist.
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
            )
            .apply {
                if (s.hasTrack) {
                    add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    add(COMMAND_SEEK_TO_DEFAULT_POSITION)
                    if (s.hasNext) addAll(COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    if (s.hasPrevious) addAll(COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
            }
            .build()

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setShuffleModeEnabled(s.shuffle)
            .setRepeatMode(
                when (s.repeat) {
                    1 -> REPEAT_MODE_ALL
                    2 -> REPEAT_MODE_ONE
                    else -> REPEAT_MODE_OFF
                },
            )

        if (!s.hasTrack) {
            return builder
                .setPlaybackState(STATE_IDLE)
                .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .build()
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.artist)
            .setAlbumTitle(s.album)
            .setDisplayTitle(s.title)
            .setSubtitle(s.artist)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                if (s.artUrl.isNotEmpty()) setArtworkUri(s.artUrl.toUri())
                setUserRating(androidx.media3.common.HeartRating(s.liked))
            }
            .build()

        val current = MediaItemData.Builder("track:${s.uri}")
            .setMediaItem(MediaItem.Builder().setMediaId(s.uri).setUri(s.uri).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(if (s.durationMs > 0) s.durationMs * 1000 else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()

        val playlist = listOf(placeholder("previous"), current, placeholder("next"))
        val playing = !s.isPaused
        val base = s.positionMs
        val measuredAt = s.measuredAt
        val duration = s.durationMs

        return builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(CURRENT_INDEX)
            .setContentPositionMs {
                val elapsed = if (playing) SystemClock.elapsedRealtime() - measuredAt else 0
                val position = base + elapsed
                if (duration > 0) position.coerceIn(0, duration) else position
            }
            .setPlaybackState(STATE_READY)
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .build()
    }

    private fun placeholder(id: String) = MediaItemData.Builder(id)
        .setMediaItem(MediaItem.Builder().setMediaId(id).build())
        .setDurationUs(C.TIME_UNSET)
        .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) PlaybackController.play() else PlaybackController.pause()
        // Optimistic update so the notification reacts immediately.
        web = web.copy(isPaused = !playWhenReady, positionMs = currentPosition, measuredAt = SystemClock.elapsedRealtime())
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        PlaybackController.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when {
            seekCommand == COMMAND_SEEK_TO_NEXT || seekCommand == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                mediaItemIndex > CURRENT_INDEX -> PlaybackController.next()

            seekCommand == COMMAND_SEEK_TO_PREVIOUS || seekCommand == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                mediaItemIndex < CURRENT_INDEX -> PlaybackController.previous()

            else -> {
                val target = if (positionMs == C.TIME_UNSET) 0 else positionMs
                PlaybackController.seek(target)
                web = web.copy(positionMs = target, measuredAt = SystemClock.elapsedRealtime())
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled != web.shuffle) PlaybackController.toggleShuffle()
        web = web.copy(shuffle = shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        // Spotify cycles off -> context -> track; step until the target is reached.
        val target = when (repeatMode) {
            REPEAT_MODE_ALL -> 1
            REPEAT_MODE_ONE -> 2
            else -> 0
        }
        val steps = (target - web.repeat + 3) % 3
        repeat(steps) { PlaybackController.toggleRepeat() }
        web = web.copy(repeat = target)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    /**
     * An item was chosen in Android Auto or requested by voice. The web player
     * owns the queue, so the item is turned into a play command instead of
     * replacing this player's (mirrored) playlist.
     */
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val item = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
        if (item != null) play(item)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        mediaItems.firstOrNull()?.let { play(it) }
        return Futures.immediateVoidFuture()
    }

    // The mirrored playlist cannot be edited; these are accepted as no-ops.
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun play(item: MediaItem) {
        val query = item.requestMetadata.searchQuery
        when {
            item.mediaId.isNotEmpty() && item.mediaId != "hint" -> PlaybackController.playItem(item.mediaId)
            !query.isNullOrBlank() -> PlaybackController.playSearch(query)
            else -> PlaybackController.play()
        }
    }

    companion object {
        private const val CURRENT_INDEX = 1
    }
}
