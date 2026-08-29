package com.v2ray.ang.automode

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * The rule that decides when a run stops looking and connects: a server is good enough
 * when it carries 70% of what the user's own line carries, measured the same way.
 */
class AutoModeAcceptanceTest {

    private fun measurement(speed: Double, delay: Long = 200) = AutoModeMeasurement(
        guid = "g",
        profile = ProfileItem(configType = EConfigType.VLESS, server = "1.2.3.4", serverPort = "443"),
        speedMbps = speed,
        delayMillis = delay,
    )

    // Never touched: isAcceptable only reads its arguments.
    private val engine = AutoModeEngine(mock(Context::class.java))

    @Test
    fun `the threshold is the configured fraction of the line`() {
        val store = AutoModeStore(acceptFraction = 0.70)
        assertEquals(7.0, AutoModeBaseline.acceptThreshold(10.0, store), 0.0001)
    }

    /**
     * With no baseline the bar drops to zero rather than to some invented number. A run
     * on an unmeasurable connection then accepts the first server that carries traffic —
     * worse than a measured bar, and much better than never connecting.
     */
    @Test
    fun `an unmeasured line sets no bar at all`() {
        val store = AutoModeStore()
        assertEquals(0.0, AutoModeBaseline.acceptThreshold(AutoModeBaseline.UNKNOWN, store), 0.0001)
    }

    @Test
    fun `a server at the threshold is accepted and one below it is not`() {
        assertTrue(engine.isAcceptable(measurement(7.0), 7.0))
        assertTrue(engine.isAcceptable(measurement(9.9), 7.0))
        assertFalse(engine.isAcceptable(measurement(6.9), 7.0))
    }

    /**
     * Throughput alone is not enough. A server that downloads quickly but answers in three
     * seconds is unusable for anything interactive, and the delay stages already measured
     * that — so the acceptance rule reads both.
     */
    @Test
    fun `a fast server with an unusable delay is still rejected`() {
        assertFalse(engine.isAcceptable(measurement(20.0, delay = 9_000), 7.0))
        assertFalse(engine.isAcceptable(measurement(20.0, delay = -1), 7.0))
    }

    @Test
    fun `a server that carried nothing is rejected even with no bar`() {
        assertFalse(engine.isAcceptable(measurement(0.0), 0.0))
    }

    /** With the bar at zero, anything that moved bytes and answered in time is taken. */
    @Test
    fun `with no bar the first working server is accepted`() {
        assertTrue(engine.isAcceptable(measurement(0.2), 0.0))
    }

    // ---------- Iran mode ----------

    private fun iranian(
        speed: Double,
        delay: Long = 200,
        carried: Boolean = true,
        exit: String? = "IR",
    ) = AutoModeMeasurement(
        guid = "g",
        // An address inside Iran's allocated space, so isIranianExit can answer without a
        // measured country — which is the case the fallback exists for.
        profile = ProfileItem(configType = EConfigType.VLESS, server = "2.144.0.1", serverPort = "443"),
        speedMbps = speed,
        delayMillis = delay,
        exitCountry = exit,
        carriedRequest = carried,
    )

    private fun german(speed: Double, delay: Long = 200) = AutoModeMeasurement(
        guid = "g",
        profile = ProfileItem(configType = EConfigType.VLESS, server = "5.9.1.1", serverPort = "443"),
        speedMbps = speed,
        delayMillis = delay,
        exitCountry = "DE",
        carriedRequest = speed > 0,
    )

    /**
     * The regression this mode was reported for. The throughput probe leaves Iran, so on a
     * throttled or filtered international link a perfectly good Iranian server measures
     * zero — and zero used to mean rejected, which left the mode with nothing to connect
     * to however many Iranian servers it had found.
     */
    @Test
    fun `an iranian server that measured no throughput is still accepted`() {
        assertTrue(engine.isAcceptable(iranian(0.0), 7.0, iranMode = true))
    }

    /** Outside the mode, zero throughput still means unusable. */
    @Test
    fun `zero throughput is still fatal in the ordinary mode`() {
        assertFalse(engine.isAcceptable(measurement(0.0), 0.0, iranMode = false))
    }

    /**
     * The route runs into Iran rather than out of it and is slower for it. Three seconds
     * is an ordinary Iranian server, not a broken one.
     */
    @Test
    fun `the delay ceiling is looser in iran mode`() {
        assertTrue(engine.isAcceptable(iranian(1.0, delay = 3_500), 0.0, iranMode = true))
        assertFalse(engine.isAcceptable(measurement(1.0, delay = 3_500), 0.0, iranMode = false))
    }

    /** Looser is not absent: a link this slow will not carry a bank session either. */
    @Test
    fun `an unusable delay is still rejected in iran mode`() {
        assertFalse(engine.isAcceptable(iranian(20.0, delay = 9_000), 0.0, iranMode = true))
        assertFalse(engine.isAcceptable(iranian(20.0, delay = -1), 0.0, iranMode = true))
    }

    /**
     * The point of the mode. A foreign server is not a slower answer here, it is the wrong
     * one — the bank would refuse it — so no amount of throughput buys it a slot.
     */
    @Test
    fun `a fast foreign server is never accepted in iran mode`() {
        assertFalse(engine.isAcceptable(german(50.0), 0.0, iranMode = true))
        assertTrue(engine.isAcceptable(german(50.0), 0.0, iranMode = false))
    }

    /**
     * 🔴 The hole that dropping the throughput gate opened.
     *
     * Removing `speedMbps > 0` in Iran mode removed the only proof that anything worked.
     * What was left was a delay — which for a champion is whatever MMKV kept from a
     * previous run, since champions skip the real-ping stage — and an address block, which
     * a server that died overnight still sits in. So a dead Iranian server passed, and was
     * published, and the user was connected to it.
     *
     * `carriedRequest` is the replacement proof: bytes moved, or a request came back
     * through this proxy just now.
     */
    @Test
    fun `a dead iranian server is not accepted on its address alone`() {
        val dead = iranian(speed = 0.0, delay = 1_800, carried = false, exit = null)
        assertFalse(engine.isAcceptable(dead, 0.0, iranMode = true))
    }

    /** The same server, once it has actually answered, is the case the mode exists for. */
    @Test
    fun `an iranian server that answered but measured nothing is accepted`() {
        val alive = iranian(speed = 0.0, delay = 1_800, carried = true, exit = null)
        assertTrue(engine.isAcceptable(alive, 7.0, iranMode = true))
    }

    /**
     * When there IS a figure it still has to clear the bar. IranMode.ACCEPT_FRACTION has
     * already lowered that bar to a tenth of the line; ignoring it entirely would have made
     * that constant, and the baseline measured to feed it, dead code.
     */
    @Test
    fun `a measured iranian server below the bar is still rejected`() {
        assertFalse(engine.isAcceptable(iranian(0.5), 2.0, iranMode = true))
        assertTrue(engine.isAcceptable(iranian(2.5), 2.0, iranMode = true))
    }
}
