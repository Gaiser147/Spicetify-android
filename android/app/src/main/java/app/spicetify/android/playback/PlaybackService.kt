package app.spicetify.android.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.spicetify.android.MainActivity
import app.spicetify.android.R
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Media session for the web player.
 *
 * - Notification, lock screen, Bluetooth and headset controls (Media3 builds
 *   them from [WebPlayer]). Media3 promotes the service to the foreground while
 *   playing, which keeps the WebView process alive in the background.
 * - Android Auto / Automotive: a browsable library (recently played, liked
 *   songs, playlists, albums), search and voice requests ("play … on
 *   Spicetify"). The data comes from the web player through the bridge, so
 *   the app has to be open on the phone.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var player: WebPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Last search results per query, for onGetSearchResult. */
    private val searchResults = ConcurrentHashMap<String, List<MediaItem>>()

    override fun onCreate() {
        super.onCreate()
        player = WebPlayer()
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setId("spicetify")
            .setSessionActivity(activityIntent)
            .build()

        scope.launch {
            PlaybackController.state.collect { player.update(it) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The WebView lives in the activity; without it there is nothing to control.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // --- Library tree ------------------------------------------------------------

    private fun folder(id: String, title: String, playable: Boolean = false): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(playable)
                    .setMediaType(
                        if (id == ROOT) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                    )
                    .build(),
            )
            .build()

    private fun rootChildren(): List<MediaItem> = listOf(
        folder(RECENT, getString(R.string.auto_recent)),
        folder(LIKED, getString(R.string.auto_liked), playable = true),
        folder(PLAYLISTS, getString(R.string.auto_playlists)),
        folder(ALBUMS, getString(R.string.auto_albums)),
    )

    private fun hint(text: String) = MediaItem.Builder()
        .setMediaId("hint")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(text).setIsBrowsable(false).setIsPlayable(false).build())
        .build()

    private fun LibraryItem.toMediaItem(): MediaItem {
        val type = when {
            id.startsWith("playlist|") -> MediaMetadata.MEDIA_TYPE_PLAYLIST
            id.startsWith("album|") -> MediaMetadata.MEDIA_TYPE_ALBUM
            id.startsWith("play|spotify:artist:") -> MediaMetadata.MEDIA_TYPE_ARTIST
            else -> MediaMetadata.MEDIA_TYPE_MUSIC
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setSubtitle(subtitle)
                    .setArtworkUri(ArtworkProvider.contentUri(artUrl))
                    .setIsBrowsable(browsable)
                    .setIsPlayable(playable)
                    .setMediaType(type)
                    .build(),
            )
            .build()
    }

    /** Asks the web player for a node's children (or search results). */
    private fun requestItems(method: String, argument: String): ListenableFuture<List<MediaItem>?> {
        val future = SettableFuture.create<List<MediaItem>?>()
        val requester = PlaybackController.requester
        if (requester == null || !requester.request(method, argument) { json ->
                future.set(LibraryItem.parseResult(json)?.map { it.toMediaItem() })
            }
        ) {
            future.set(null)
        }
        return future
    }

    private fun childrenResult(
        items: List<MediaItem>?,
        params: LibraryParams?,
        page: Int,
        pageSize: Int,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val list = when {
            items == null -> listOf(hint(getString(if (PlaybackController.isPlayerAvailable) R.string.auto_error else R.string.auto_open_app)))
            items.isEmpty() -> listOf(hint(getString(R.string.auto_empty)))
            else -> items
        }
        val from = (page * pageSize).coerceAtMost(list.size)
        val to = if (pageSize <= 0) list.size else (from + pageSize).coerceAtMost(list.size)
        return LibraryResult.ofItemList(ImmutableList.copyOf(list.subList(from, to)), params)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootParams = LibraryParams.Builder()
                .setExtras(Bundle().apply { putBoolean(CONTENT_STYLE_SUPPORTED, true) })
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, getString(R.string.app_name)), params ?: rootParams))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = rootChildren().firstOrNull { it.mediaId == mediaId }
                ?: MediaItem.Builder().setMediaId(mediaId).build()
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == ROOT) return Futures.immediateFuture(childrenResult(rootChildren(), params, page, pageSize))
            return Futures.transform(
                requestItems("browse", parentId),
                { childrenResult(it, params, page, pageSize) },
                Runnable::run,
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val future = requestItems("search", query)
            future.addListener({
                val items = runCatching { future.get() }.getOrNull()
                val results = items ?: emptyList()
                searchResults[query] = results
                session.notifySearchResultChanged(browser, query, results.size, params)
            }, Runnable::run)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val cached = searchResults[query]
            if (cached != null) return Futures.immediateFuture(childrenResult(cached, params, page, pageSize))
            return Futures.transform(
                requestItems("search", query),
                { childrenResult(it.also { r -> if (r != null) searchResults[query] = r }, params, page, pageSize) },
                Runnable::run,
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // Items only carry ids (or a voice search query); WebPlayer turns
            // them into play commands for the web player.
            return Futures.immediateFuture(mediaItems)
        }
    }

    companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val LIKED = "liked"
        const val PLAYLISTS = "playlists"
        const val ALBUMS = "albums"

        // Android Auto content style hints.
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    }
}
