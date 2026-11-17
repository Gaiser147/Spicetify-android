package app.spicetify.android

import app.spicetify.android.playback.ArtworkProvider
import app.spicetify.android.playback.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test
    fun parsesBridgeResult() {
        val json = """{"ok":true,"items":[
            {"id":"playlist|spotify:playlist:1","title":"Mix","subtitle":"me","artUrl":"https://i.scdn.co/image/a","playable":true,"browsable":true},
            {"id":"","title":"broken"},
            {"id":"play||spotify:track:2","title":"Song","playable":true}
        ]}"""
        val items = LibraryItem.parseResult(json)!!
        assertEquals(2, items.size)
        assertEquals("Mix", items[0].title)
        assertTrue(items[0].browsable)
        assertFalse(items[1].browsable)
    }

    @Test
    fun errorsReturnNull() {
        assertNull(LibraryItem.parseResult("""{"ok":false,"error":"timeout"}"""))
        assertNull(LibraryItem.parseResult("not json"))
    }

    @Test
    fun artworkHostsAreRestricted() {
        assertTrue(ArtworkProvider.isAllowed("https://i.scdn.co/image/ab67616d0000b273"))
        assertTrue(ArtworkProvider.isAllowed("https://image-cdn-fa.spotifycdn.com/image/ab67706c"))
        assertFalse(ArtworkProvider.isAllowed("http://i.scdn.co/image/x"))
        assertFalse(ArtworkProvider.isAllowed("https://example.com/image.jpg"))
        assertFalse(ArtworkProvider.isAllowed("https://scdn.co.evil.net/x"))
        assertFalse(ArtworkProvider.isAllowed("file:///etc/passwd"))
    }
}
