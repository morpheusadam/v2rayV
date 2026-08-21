package com.v2ray.ang.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a run is allowed to conclude about a subscription link, and how the source list
 * gets back up when it has concluded the worst about all of them.
 *
 * The two failures this pins down are the same failure seen from both ends. A link is
 * auto-disabled after five runs that produced nothing; a network that blocks the list host
 * produces nothing from every link at once. On a censored connection — which is the one
 * this app exists for — a few hours of that used to be enough to switch the entire source
 * list off on evidence that was never about the links, and nothing switched it back on.
 */
class AutoModeSourceHealthTest {

    private fun deadSource(url: String, lastTried: Long = 0) = AutoModeSource(
        url = url,
        enabled = false,
        autoDisabled = true,
        tried = 9,
        deadStreak = 5,
        lastTriedMillis = lastTried,
    )

    @Test
    fun `five dead runs disable a link`() {
        val source = AutoModeSource(url = "https://example.com/a.txt")
        repeat(5) {
            AutoModeSourceManager.applyResult(source, 0, 0, 0, true, null, 0, 0.0)
        }
        assertFalse(source.enabled)
        assertTrue(source.autoDisabled)
    }

    /**
     * The fix for the death spiral. A run that downloaded nothing at all has learned
     * something about the connection and nothing about the link, so it records the attempt
     * and leaves the evidence alone.
     */
    @Test
    fun `a run that reached nothing does not count against a link`() {
        val source = AutoModeSource(url = "https://example.com/a.txt")
        repeat(20) { AutoModeSourceManager.noteNetworkDown(source) }

        assertEquals(20, source.tried)
        assertEquals(0, source.deadStreak)
        assertEquals(1.0, source.beta, 0.0001)
        assertTrue(source.enabled)
    }

    /** One good run clears the streak, so a link is not condemned by an old bad patch. */
    @Test
    fun `a working run revives a link that had been auto-disabled`() {
        val source = deadSource("https://example.com/a.txt")
        AutoModeSourceManager.applyResult(source, 10, 4, 1, false, "hash", 10, 3.0)

        assertTrue(source.enabled)
        assertFalse(source.autoDisabled)
        assertEquals(0, source.deadStreak)
    }

    /**
     * With nothing enabled the sampler has nothing to draw from, and the once-every-fifth-run
     * probe would take days to find its way out. That state is the network's doing far more
     * often than it is four hundred links dying together, so it is treated as such.
     */
    @Test
    fun `a source list that has disabled itself entirely comes back at full width`() {
        val store = AutoModeStore(
            sourcesPerRun = 8,
            // Not a multiple of the resurrection interval, so the probe is not what answers.
            runCount = 3,
            sources = (1..20).map { deadSource("https://example.com/$it.txt", lastTried = it.toLong()) }
                .toMutableList(),
        )

        val picked = AutoModeSourceManager.selectSources(store)

        assertEquals(8, picked.size)
        // Oldest attempt first: the links that have waited longest are tried first.
        assertEquals("https://example.com/1.txt", picked.first().url)
    }

    @Test
    fun `a source list with nothing in it at all asks for nothing`() {
        assertTrue(AutoModeSourceManager.selectSources(AutoModeStore()).isEmpty())
    }

    /** A link the user switched off by hand stays off; only auto-disabled ones come back. */
    @Test
    fun `the recovery does not re-open a link the user turned off`() {
        val store = AutoModeStore(
            runCount = 3,
            sources = mutableListOf(
                AutoModeSource(url = "https://example.com/user-off.txt", enabled = false, tried = 4),
                deadSource("https://example.com/auto-off.txt", lastTried = 1),
            ),
        )

        val picked = AutoModeSourceManager.selectSources(store)

        assertEquals(listOf("https://example.com/auto-off.txt"), picked.map { it.url })
    }
}
