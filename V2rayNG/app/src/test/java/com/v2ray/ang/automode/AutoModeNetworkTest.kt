package com.v2ray.ang.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModeNetworkTest {

    /**
     * The default. A mirror discloses the request to whoever runs it, so nothing is offered
     * until the user has said so — see [com.v2ray.ang.automode.AutoModeStore.mirrorsEnabled].
     */
    @Test
    fun `offers no mirror until the user turns them on`() {
        assertEquals(
            emptyList<String>(),
            AutoModeNetwork.mirrorsFor(subsUrl, enabled = false, index = 0)
        )
    }

    @Test
    fun `offers the chosen mirror once they are on`() {
        // A static mirror: the repository layout is reproduced under the host, so this is the
        // upstream URL with the host swapped and nothing else.
        assertEquals(
            listOf("https://cdn.lavzen.com/subs/all.txt"),
            AutoModeNetwork.mirrorsFor(subsUrl, enabled = true, index = 0)
        )
        assertEquals(
            listOf("https://cdn.jsdelivr.net/gh/morpheusadam/v2ray-config@main/subs/all.txt"),
            AutoModeNetwork.mirrorsFor(subsUrl, enabled = true, index = 1)
        )
    }

    /**
     * Only the chosen one. Walking the whole list would tell every operator on it what was
     * asked for, to save a single failed fetch.
     */
    @Test
    fun `offers exactly one mirror, never the whole list`() {
        AutoModeNetwork.MIRRORS.indices.forEach { i ->
            assertEquals(1, AutoModeNetwork.mirrorsFor(subsUrl, enabled = true, index = i).size)
        }
    }

    /**
     * A stored index from a build that offered more mirrors must not silently disable the
     * feature the user switched on.
     */
    @Test
    fun `an index that names nothing falls back to the first mirror`() {
        val first = AutoModeNetwork.mirrorsFor(subsUrl, enabled = true, index = 0)
        assertEquals(first, AutoModeNetwork.mirrorsFor(subsUrl, enabled = true, index = 99))
        assertEquals(first, AutoModeNetwork.mirrorsFor(subsUrl, enabled = true, index = -1))
    }

    /** This project's own mirror is the one offered first; see the ordering rationale there. */
    @Test
    fun `this project's own mirror is first`() {
        assertTrue(AutoModeNetwork.MIRRORS.first().name.contains("v2rayV"))
    }

    /**
     * GitHub serves the same file under `/refs/heads/main/` as under `/main/`. A mirror
     * built from the longer form has to drop the prefix or it addresses nothing.
     */
    @Test
    fun `normalises the refs heads form when building mirrors`() {
        // Checked on jsDelivr, whose URL carries the ref: the longer form must not leak into
        // it. The static mirror drops the ref entirely, so it cannot show the bug.
        val mirrors = AutoModeNetwork.mirrorsFor(
            "https://raw.githubusercontent.com/user/repo/refs/heads/main/config.txt",
            enabled = true,
            index = 1,
        )

        assertTrue(mirrors.first().endsWith("/gh/user/repo@main/config.txt"))
    }

    @Test
    fun `has no mirrors for a host it does not know how to rewrite`() {
        assertEquals(
            emptyList<String>(),
            AutoModeNetwork.mirrorsFor("https://example.com/list.txt", enabled = true, index = 0)
        )
    }

    private val subsUrl = "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/all.txt"

    /** Two mirrors' worth of ladder, standing in for whatever the user has chosen. */
    private val chosen = listOf("https://mirror.example/a.txt", "https://mirror.example/b.txt")

    /**
     * With mirrors off — the default — the ladder is one rung. The app asks the host the list
     * actually lives on, and nobody else.
     */
    @Test
    fun `the ladder is just the host until mirrors are turned on`() {
        assertEquals(listOf(subsUrl), AutoModeNetwork.routes(subsUrl, mirrors = emptyList()))
    }

    @Test
    fun `the ladder starts at the host and falls through to the mirrors`() {
        val routes = AutoModeNetwork.routes(subsUrl, mirrors = chosen)

        assertEquals(3, routes.size)
        assertEquals(subsUrl, routes.first())
    }

    /**
     * The rung that answered last time goes first. Everything else keeps its order, because
     * that order is still the right one to fall through when the remembered rung has since
     * been blocked as well.
     */
    @Test
    fun `a remembered rung is tried first without reshuffling the rest`() {
        val all = AutoModeNetwork.routes(subsUrl, mirrors = chosen)
        val reordered = AutoModeNetwork.routes(subsUrl, preferred = 2, mirrors = chosen)

        assertEquals(all[2], reordered.first())
        assertEquals(listOf(all[0], all[1]), reordered.drop(1))
    }

    /**
     * A stored index survives an upgrade that changes the mirror list, so it can name a rung
     * that no longer exists. That must fall back to the normal order rather than throw.
     */
    @Test
    fun `an out of range memory is ignored`() {
        val all = AutoModeNetwork.routes(subsUrl, mirrors = chosen)

        assertEquals(all, AutoModeNetwork.routes(subsUrl, preferred = 99, mirrors = chosen))
        assertEquals(all, AutoModeNetwork.routes(subsUrl, preferred = -1, mirrors = chosen))
        assertEquals(all, AutoModeNetwork.routes(subsUrl, preferred = 0, mirrors = chosen))
    }

    /** A URL with no mirrors is a one-rung ladder whatever is remembered. */
    @Test
    fun `a host with no mirrors has nothing to reorder`() {
        assertEquals(
            listOf("https://example.com/list.txt"),
            AutoModeNetwork.routes("https://example.com/list.txt", 2, mirrors = emptyList())
        )
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
