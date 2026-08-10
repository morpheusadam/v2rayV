package com.v2ray.ang.automode

import org.junit.Assert.assertTrue
import org.junit.Test

class BetaSamplerTest {

    @Test
    fun `draws stay inside the unit interval`() {
        repeat(2000) {
            val x = BetaSampler.sample(1.0, 1.0)
            assertTrue("draw out of range: $x", x in 0.0..1.0)
        }
    }

    /**
     * The property the source picker actually depends on: strong evidence of success
     * should usually win a draw against strong evidence of failure. "Usually" rather than
     * "always" is the point — that residual chance is what re-tries a link that looks bad.
     */
    @Test
    fun `strong evidence usually beats weak evidence`() {
        var good = 0
        repeat(1000) {
            val strong = BetaSampler.sample(40.0, 2.0)
            val weak = BetaSampler.sample(2.0, 40.0)
            if (strong > weak) good++
        }
        assertTrue("strong source won only $good/1000 draws", good > 950)
    }

    /**
     * An unexplored link has a uniform posterior, so it must sometimes out-draw a link
     * with a moderate record; without that, a new link would never get its first chance.
     */
    @Test
    fun `an unexplored source still wins draws against a moderate one`() {
        var wins = 0
        repeat(1000) {
            val fresh = BetaSampler.sample(1.0, 1.0)
            val moderate = BetaSampler.sample(6.0, 4.0)
            if (fresh > moderate) wins++
        }
        assertTrue("fresh source never explored: $wins/1000", wins in 100..700)
    }

    @Test
    fun `degenerate parameters do not blow up`() {
        repeat(500) {
            val x = BetaSampler.sample(0.0, 0.0)
            assertTrue(x in 0.0..1.0)
        }
        repeat(500) {
            assertTrue(BetaSampler.sampleGamma(0.1) >= 0.0)
        }
    }
}
