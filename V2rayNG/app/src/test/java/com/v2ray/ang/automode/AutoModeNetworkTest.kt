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
