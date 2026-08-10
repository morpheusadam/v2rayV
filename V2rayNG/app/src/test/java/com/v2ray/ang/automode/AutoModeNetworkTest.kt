package com.v2ray.ang.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModeNetworkTest {

    @Test
    fun `offers CDN mirrors for a github raw url`() {
        val mirrors = AutoModeNetwork.mirrorsFor(
            "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/all.txt"
        )

        assertEquals(
            listOf(
                "https://cdn.jsdelivr.net/gh/morpheusadam/v2ray-config@main/subs/all.txt",
                "https://raw.githack.com/morpheusadam/v2ray-config/main/subs/all.txt",
                "https://gitcdn.link/cdn/morpheusadam/v2ray-config/main/subs/all.txt",
            ),
            mirrors
        )
    }

    /**
     * GitHub serves the same file under `/refs/heads/main/` as under `/main/`. A mirror
     * built from the longer form has to drop the prefix or it addresses nothing.
     */
    @Test
    fun `normalises the refs heads form when building mirrors`() {
        val mirrors = AutoModeNetwork.mirrorsFor(
            "https://raw.githubusercontent.com/user/repo/refs/heads/main/config.txt"
        )

        assertTrue(mirrors.first().endsWith("/gh/user/repo@main/config.txt"))
    }

    @Test
    fun `has no mirrors for a host it does not know how to rewrite`() {
        assertEquals(emptyList<String>(), AutoModeNetwork.mirrorsFor("https://example.com/list.txt"))
    }

    private val subsUrl = "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/all.txt"

    @Test
    fun `the ladder starts at the host and falls through to the mirrors`() {
        val routes = AutoModeNetwork.routes(subsUrl)

        assertEquals(4, routes.size)
        assertEquals(subsUrl, routes.first())
    }

    /**
     * The rung that answered last time goes first. Everything else keeps its order, because
     * that order is still the right one to fall through when the remembered rung has since
     * been blocked as well.
     */
    @Test
    fun `a remembered rung is tried first without reshuffling the rest`() {
        val all = AutoModeNetwork.routes(subsUrl)
        val reordered = AutoModeNetwork.routes(subsUrl, preferred = 2)

        assertEquals(all[2], reordered.first())
        assertEquals(listOf(all[0], all[1], all[3]), reordered.drop(1))
    }

    /**
     * A stored index survives an upgrade that changes the mirror list, so it can name a rung
     * that no longer exists. That must fall back to the normal order rather than throw.
     */
    @Test
    fun `an out of range memory is ignored`() {
        val all = AutoModeNetwork.routes(subsUrl)

        assertEquals(all, AutoModeNetwork.routes(subsUrl, preferred = 99))
        assertEquals(all, AutoModeNetwork.routes(subsUrl, preferred = -1))
        assertEquals(all, AutoModeNetwork.routes(subsUrl, preferred = 0))
    }

    /** A URL with no mirrors is a one-rung ladder whatever is remembered. */
    @Test
    fun `a host with no mirrors has nothing to reorder`() {
        assertEquals(listOf("https://example.com/list.txt"), AutoModeNetwork.routes("https://example.com/list.txt", 2))
    }

    /**
     * The size used to come from a second, identical range request. It is now read off the
     * probe that found the route, so the parsing has to work on a whole response.
     */
    @Test
    fun `reads the size off the probe response that found the route`() {
        val probe = ProxiedFetch.Response(
            code = 206,
            body = ByteArray(1),
            headers = mapOf("content-range" to "bytes 0-0/987654"),
        )

        assertEquals(987654L, AutoModeNetwork.sizeOf(probe))
    }

    /**
     * 200 means the server ignored the range and sent the lot, which is the signal that
     * windowed sampling is not available — not a size of zero.
     */
    @Test
    fun `a server that ignored the range reports no size`() {
        val whole = ProxiedFetch.Response(
            code = 200,
            body = ByteArray(1),
            headers = mapOf("content-length" to "987654"),
        )

        assertNull(AutoModeNetwork.sizeOf(whole))
    }

    @Test
    fun `reads the total size out of a content-range header`() {
        assertEquals(123456L, AutoModeNetwork.parseContentRangeTotal("bytes 0-0/123456"))
    }

    /**
     * A server that does not know the total answers with an asterisk. That is the signal
     * to download the file whole rather than sample it, so it must not parse as a size.
     */
    @Test
    fun `treats an unknown total as no answer`() {
        assertNull(AutoModeNetwork.parseContentRangeTotal("bytes 0-0/*"))
        assertNull(AutoModeNetwork.parseContentRangeTotal(null))
        assertNull(AutoModeNetwork.parseContentRangeTotal("nonsense"))
    }

    /**
     * The shipped catalog is a list of other people's subscription links, with a comment
     * header. Reading it as a *source* would import nothing at all — the engine strips
     * bare subscription URLs out of any body it imports — so what matters is that it
     * parses as links, and that the header is not mistaken for one.
     */
    @Test
    fun `reads the catalog as links and ignores its comment header`() {
        val catalog = """
            # Subscription sources for Auto Mode
            #
            # Lines starting with # are ignored, so notes like this are fine.

            https://raw.githubusercontent.com/crackbest/V2ray-Config/refs/heads/main/config.txt
            https://raw.githubusercontent.com/barry-far/V2ray-config/main/Sub1.txt
        """.trimIndent()

        val links = AutoModeSourceManager.parseUrls(catalog)
        assertEquals(2, links.size)
        assertTrue(links.all { it.startsWith("https://raw.githubusercontent.com/") })
    }
}
