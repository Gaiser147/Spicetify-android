package app.spicetify.android

import app.spicetify.android.web.UserAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentsTest {
    private val webView159 =
        "Mozilla/5.0 (Linux; Android 16; SM-X710 Build/BP2A; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/159.0.8154.4 Safari/537.36"

    @Test
    fun desktopAgentUsesWebViewChromeVersion() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/159.0.0.0 Safari/537.36",
            UserAgents.desktop(webView159),
        )
        assertEquals("159", UserAgents.chromeMajor(UserAgents.desktop(webView159)))
    }

    @Test
    fun fallsBackWithoutVersion() {
        assertTrue(UserAgents.desktop(null).contains("Chrome/140.0.0.0"))
        assertTrue(UserAgents.desktop("something else").contains("X11; Linux x86_64"))
    }

    @Test
    fun loginHosts() {
        assertTrue(UserAgents.isLoginHost("accounts.spotify.com"))
        assertTrue(UserAgents.isLoginHost("challenge.spotify.com"))
        assertFalse(UserAgents.isLoginHost("open.spotify.com"))
        assertFalse(UserAgents.isLoginHost(null))
    }

    @Test
    fun requestedWithHeaderIsNeverForwarded() {
        assertFalse(UserAgents.isForwardedHeader("X-Requested-With"))
        assertFalse(UserAgents.isForwardedHeader("x-requested-with", "Range"))
        assertFalse(UserAgents.isForwardedHeader("range", "Range"))
        assertTrue(UserAgents.isForwardedHeader("Accept", "Range", "Cookie"))
    }

    @Test
    fun describesClientHeaders() {
        val text = UserAgents.describeHeaders(mapOf("x-requested-with" to "app.spicetify.android", "Sec-CH-UA-Platform" to "\"Linux\""))
        assertTrue(text.contains("x-requested-with=app.spicetify.android"))
        assertTrue(text.contains("sec-ch-ua-platform=\"Linux\""))
        assertTrue(text.contains("sec-ch-ua-mobile=-"))
    }
}
