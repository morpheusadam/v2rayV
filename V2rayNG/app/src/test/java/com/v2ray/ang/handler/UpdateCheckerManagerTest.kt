package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version ordering decides whether an installed app is ever told an update exists, so the
 * cases that matter are the tags a release actually gets named, not just clean triples.
 */
class UpdateCheckerManagerTest {

    private fun cmp(a: String, b: String) = UpdateCheckerManager.compareVersions(a, b)

    @Test
    fun `orders ordinary versions`() {
        assertTrue(cmp("2.3.5", "2.3.4") > 0)
        assertTrue(cmp("2.3.4", "2.3.5") < 0)
        assertTrue(cmp("3.0.0", "2.9.9") > 0)
        assertEquals(0, cmp("2.3.4", "2.3.4"))
    }

    /**
     * The case that broke it. A rebuild of a release keeps its name and gains a suffix, and
     * the old code called toInt() on "4-1" and threw, which failed the whole update check
     * rather than the comparison.
     */
    @Test
    fun `a rebuild of a version is newer than the version`() {
        assertTrue(cmp("2.3.4-1", "2.3.4") > 0)
        assertTrue(cmp("2.3.4", "2.3.4-1") < 0)
        assertTrue(cmp("2.3.4-2", "2.3.4-1") > 0)
    }

    @Test
    fun `tags with words in them do not throw`() {
        // Any result is acceptable here; not throwing is the requirement, because a throw
        // stops the installed app from ever hearing about an update again.
        cmp("1.0-rc2", "1.0")
        cmp("3.0.0-beta", "3.0.0")
        cmp("v2.3.4", "2.3.4")
        cmp("", "2.3.4")
        cmp("nightly", "2.3.4")
    }

    @Test
    fun `a missing component counts as zero`() {
        assertEquals(0, cmp("2.3", "2.3.0"))
        assertTrue(cmp("2.3.1", "2.3") > 0)
    }

    /** A date-based tag overflows an Int, which is why the parts are Long. */
    @Test
    fun `a large numeric tag does not overflow`() {
        assertTrue(cmp("20260817", "20260816") > 0)
    }
}
