package app.spicetify.android

import app.spicetify.android.data.LayoutMode
import app.spicetify.android.data.Snippet
import app.spicetify.android.data.SpicetifyConfig
import app.spicetify.android.data.SpicetifyFiles
import app.spicetify.android.web.SpotifyWebView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigAndLinksTest {
    @Test
    fun configRoundTrip() {
        val config = SpicetifyConfig(
            currentTheme = "Dribbblish",
            colorScheme = "nord-dark",
            extensions = listOf("bookmark.js", "fullAppDisplay.js"),
            customApps = listOf("marketplace"),
            snippets = listOf(Snippet("1", "Hide ads", ".ad { display: none }", enabled = false)),
            layoutMode = LayoutMode.MOBILE,
            disableLogging = false,
        )
        val parsed = SpicetifyConfig.fromJson(JSONObject(config.toJson().toString()))
        assertEquals(config, parsed)
    }

    @Test
    fun missingKeysUseDefaults() {
        val parsed = SpicetifyConfig.fromJson(JSONObject("""{"currentTheme":"X"}"""))
        assertEquals("X", parsed.currentTheme)
        assertEquals(SpicetifyConfig().customApps, parsed.customApps)
        assertEquals(LayoutMode.AUTO, parsed.layoutMode)
    }

    @Test
    fun internalHosts() {
        assertTrue(SpotifyWebView.isInternalHost("open.spotify.com"))
        assertTrue(SpotifyWebView.isInternalHost("accounts.spotify.com"))
        assertTrue(SpotifyWebView.isInternalHost("open.spotifycdn.com"))
        assertTrue(SpotifyWebView.isInternalHost("i.scdn.co"))
        assertFalse(SpotifyWebView.isInternalHost("evil-spotify.com"))
        assertFalse(SpotifyWebView.isInternalHost("spotify.com.evil.net"))
        assertFalse(SpotifyWebView.isInternalHost(null))
    }

    @Test
    fun sanitizeNames() {
        assertEquals("my_theme", SpicetifyFiles.sanitizeName("my/theme"))
        assertEquals("imported", SpicetifyFiles.sanitizeName("   "))
    }
}
