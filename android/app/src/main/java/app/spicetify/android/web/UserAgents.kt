package app.spicetify.android.web

/**
 * User agent handling.
 *
 * The web player needs a desktop browser user agent. It must report the
 * Chrome version the WebView really runs: Spotify's login and bot protection
 * reject outdated versions and inconsistent browser fingerprints. The login
 * pages themselves run with the WebView's unmodified user agent and client
 * hints, so that what they see matches the device.
 */
object UserAgents {
    private val chromeVersion = Regex("Chrome/(\\d+)")

    /** Desktop Chrome user agent with the major version of [webViewUserAgent]. */
    fun desktop(webViewUserAgent: String?, fallbackMajor: String = "140"): String {
        val major = webViewUserAgent?.let { chromeVersion.find(it)?.groupValues?.get(1) } ?: fallbackMajor
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    fun chromeMajor(userAgent: String): String = chromeVersion.find(userAgent)?.groupValues?.get(1) ?: "140"

    private val LOGIN_HOSTS = setOf(
        "accounts.spotify.com",
        "challenge.spotify.com",
        "www.facebook.com",
        "m.facebook.com",
        "appleid.apple.com",
    )

    /** Hosts of the login flow (Spotify accounts and the login providers). */
    fun isLoginHost(host: String?): Boolean = host != null && host in LOGIN_HOSTS

    /**
     * Request headers copied from a WebView request to our own fetch. The
     * WebView's "X-Requested-With: <package name>" marks embedded WebViews;
     * Chrome does not send it, so it is never forwarded.
     */
    fun isForwardedHeader(name: String, vararg skip: String): Boolean =
        !name.equals("X-Requested-With", true) && skip.none { it.equals(name, true) }

    /** Summary of request headers relevant to how Spotify sees the client. */
    fun describeHeaders(headers: Map<String, String>): String {
        fun value(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        return listOf("X-Requested-With", "Sec-CH-UA-Platform", "Sec-CH-UA-Mobile", "Sec-CH-UA", "User-Agent")
            .joinToString(", ") { "${it.lowercase()}=${value(it) ?: "-"}" }
    }
}
