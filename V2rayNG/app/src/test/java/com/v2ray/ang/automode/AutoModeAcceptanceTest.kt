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
}
