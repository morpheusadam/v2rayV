package com.v2ray.ang.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The thing that would make this feature harmful is switching under an idle phone, so most
 * of these are about *not* firing.
 */
class SmartSwitchTest {

    /** A server measured at 4 MB/s, so "slow" means under 1 MB/s. */
    private fun switcher(referenceMbps: Double = 4.0) = SmartSwitch(referenceMbps)

    private val requesting = 50_000L      // uplink well above the request floor
    private val idle = 200L               // keepalives and DNS, nothing more
    private val healthy = 3_000_000L      // ~2.9 MB/s down
    private val nothing = 100L

    /** Feeds [count] identical seconds and returns the last verdict. */
    private fun feed(
        s: SmartSwitch,
        up: Long,
        down: Long,
        count: Int,
        startMillis: Long = 60_000,
    ): SmartSwitch.Verdict {
        var verdict: SmartSwitch.Verdict = SmartSwitch.Verdict.Stay
        repeat(count) { i ->
            verdict = s.onSample(up, down, startMillis + i * 1_000L)
        }
        return verdict
    }

    /**
     * The whole reason the uplink is consulted at all. A phone in a pocket reports zero in
     * both directions for hours, which is indistinguishable from a dead tunnel by
     * throughput alone — and switching under it would drop a working connection nightly.
     */
    @Test
    fun `an idle connection is never a reason to switch`() {
        val verdict = feed(switcher(), up = idle, down = nothing, count = 600)
        assertEquals(SmartSwitch.Verdict.Stay, verdict)
    }

    @Test
    fun `a working connection is left alone`() {
        val verdict = feed(switcher(), up = requesting, down = healthy, count = 600)
        assertEquals(SmartSwitch.Verdict.Stay, verdict)
    }

    /** Requests leaving, nothing coming back: what "connected but nothing loads" is. */
    @Test
    fun `a stalled tunnel switches once the failure is sustained`() {
        val s = switcher()

        assertEquals(SmartSwitch.Verdict.Stay, feed(s, requesting, nothing, count = 7))

        val verdict = s.onSample(requesting, nothing, 60_000 + 7_000)
        assertTrue("expected a switch, got $verdict", verdict is SmartSwitch.Verdict.Switch)
        assertTrue((verdict as SmartSwitch.Verdict.Switch).reason.contains("stopped responding"))
    }

    /** Carrying traffic, but at a fraction of what it promised when it was chosen. */
    @Test
    fun `a server far below its own measurement switches`() {
        // 4 MB/s measured, quarter of that is 1 MB/s; 300 KB/s is well under.
        val verdict = feed(switcher(4.0), up = requesting, down = 300_000, count = 8)
        assertTrue(verdict is SmartSwitch.Verdict.Switch)
        assertTrue((verdict as SmartSwitch.Verdict.Switch).reason.contains("slowed"))
    }

    /**
     * A hand-picked server has no measurement to be slow relative to. It can still stall,
     * and that is still worth acting on — but "slow" has no meaning without a reference and
     * must not be invented.
     */
    @Test
    fun `without a reference speed only a stall counts`() {
        assertEquals(
            SmartSwitch.Verdict.Stay,
            feed(switcher(referenceMbps = 0.0), up = requesting, down = 300_000, count = 60)
        )
        assertTrue(
            feed(switcher(referenceMbps = 0.0), up = requesting, down = nothing, count = 8)
                is SmartSwitch.Verdict.Switch
        )
    }

    /** One bad second in an otherwise fine minute is a hiccup, not a verdict. */
    @Test
    fun `an intermittent bad second does not accumulate`() {
        val s = switcher()
        repeat(40) { i ->
            val down = if (i % 5 == 0) nothing else healthy
            assertEquals(SmartSwitch.Verdict.Stay, s.onSample(requesting, down, 60_000 + i * 1_000L))
        }
    }

    /**
     * A fresh tunnel has to rebuild every connection the old one was carrying and looks
     * terrible while it does. Judging it during that would switch again immediately, and
     * again after that.
     */
    @Test
    fun `nothing fires during the cooldown after a switch`() {
        val s = switcher()
        s.reset(nowMillis = 100_000)

        // A full minute of total failure, all of it inside the 45s cooldown.
        repeat(40) { i ->
            assertEquals(
                SmartSwitch.Verdict.Stay,
                s.onSample(requesting, nothing, 100_000 + i * 1_000L)
            )
        }

        // Past the cooldown, the same evidence is acted on.
        val verdict = feed(s, requesting, nothing, count = 8, startMillis = 150_000)
        assertTrue(verdict is SmartSwitch.Verdict.Switch)
    }

    /**
     * Past a few switches an hour the problem is the network, not the server, and
     * reconnecting into it repeatedly only costs the user their open connections.
     */
    @Test
    fun `switching is capped so a bad network cannot cause a reconnect loop`() {
        val s = switcher()
        var clock = 60_000L
        var switches = 0

        // Six hours of a permanently broken tunnel, with the user always requesting.
        repeat(6 * 60 * 60) {
            if (s.onSample(requesting, nothing, clock) is SmartSwitch.Verdict.Switch) {
                switches++
            }
            clock += 1_000
        }

        // Four an hour, not four hundred. The exact total depends on how the cooldown lands
        // inside each window, so this checks the order of magnitude the cap is there for.
        assertTrue("switched $switches times in six hours", switches in 1..30)
    }
}
