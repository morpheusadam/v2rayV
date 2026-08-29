package com.v2ray.ang.automode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The pulse's decisions, which are the ones that decide whether a background refresh
 * happens at all.
 *
 * [AutoModePulse.run] itself is not tested here — it starts real cores and talks to MMKV —
 * but every judgement it feeds is pure and lives below.
 */
class AutoModePulseTest {

    private fun storeCheckedAgo(millis: Long) = AutoModeStore().apply {
        reserveCheckedMillis = System.currentTimeMillis() - millis
    }

    @Test
    fun `a reserve that has never been pulsed is due for one`() {
        assertTrue(AutoModePulse.isPulseDue(AutoModeStore()))
    }

    @Test
    fun `a reserve pulsed a moment ago is not pulsed again`() {
        assertFalse(AutoModePulse.isPulseDue(storeCheckedAgo(TimeUnit.MINUTES.toMillis(2))))
    }

    @Test
    fun `a reserve pulsed long enough ago is due again`() {
        assertTrue(
            AutoModePulse.isPulseDue(
                storeCheckedAgo(AutoModePulse.FOREGROUND_TTL_MILLIS + 1_000)
            )
        )
    }

    /**
     * A clock moved backwards used to be able to freeze this kind of schedule outright, by
     * making every timestamp look like it was written in the future. The age is floored, so
     * the worst a bad clock can do is ask for one extra pulse.
     */
    @Test
    fun `a timestamp in the future does not freeze the pulse`() {
        val store = AutoModeStore().apply {
            reserveCheckedMillis = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2)
        }
        assertFalse(AutoModePulse.isPulseDue(store))
    }

    /**
     * The distinction the count-based test could never make. "Never checked" is not the
     * same as "checked and fine", and reading it as the second is exactly how a reserve of
     * ten dead servers kept declining every refresh it was offered.
     */
    @Test
    fun `an unpulsed reserve is not treated as healthy`() {
        assertFalse(AutoModePulse.isReserveHealthy(AutoModeStore(), setOf("a")))
    }

    // ---------- how much of the reserve has to be answering ----------

    /** A pulsed reserve where everything answered. */
    private fun pulsed(reserve: List<String>, alive: List<String>): AutoModeStore {
        val now = System.currentTimeMillis()
        return AutoModeStore().apply {
            reserveCount = 10
            reserveCheckedMillis = now
            aliveByGuid = alive.associateWith { now }.toMutableMap()
        }
    }

    /**
     * 🔴 The regression this test exists for.
     *
     * The fraction used to be taken of `reserveCount`, the configured **target**, rather
     * than of what the reserve actually holds. With a target of ten, a reserve of five
     * live servers passes the row-count low-water test in `isRefreshDue` (5 is not below
     * 5) and could never reach the six alive the target demanded — so every scheduled
     * refresh fired, found five again, and fired again six hours later, forever, on a
     * reserve that was working perfectly.
     */
    @Test
    fun `a small reserve with everything answering is healthy`() {
        val reserve = listOf("a", "b", "c", "d", "e")
        assertTrue(AutoModePulse.isReserveHealthy(pulsed(reserve, reserve), reserve.toSet()))
    }

    @Test
    fun `a full reserve with everything answering is healthy`() {
        val reserve = (1..10).map { "g$it" }
        assertTrue(AutoModePulse.isReserveHealthy(pulsed(reserve, reserve), reserve.toSet()))
    }

    /** Below the fraction of what is held, it is not healthy — which is the point. */
    @Test
    fun `a reserve where most entries have died is not healthy`() {
        val reserve = (1..10).map { "g$it" }
        val alive = reserve.take(5)
        assertFalse(AutoModePulse.isReserveHealthy(pulsed(reserve, alive), reserve.toSet()))
    }

    @Test
    fun `at the fraction exactly it is healthy`() {
        val reserve = (1..10).map { "g$it" }
        val alive = reserve.take(6)
        assertTrue(AutoModePulse.isReserveHealthy(pulsed(reserve, alive), reserve.toSet()))
    }

    /** An entry for a server the reserve no longer holds must not prop up the count. */
    @Test
    fun `liveness for an evicted server does not count`() {
        val reserve = (1..10).map { "g$it" }
        val ghosts = (11..20).map { "g$it" }
        val store = pulsed(reserve, reserve.take(3) + ghosts)
        assertFalse(AutoModePulse.isReserveHealthy(store, reserve.toSet()))
    }

    /** An answer from long enough ago is not evidence any more. */
    @Test
    fun `stale liveness does not count`() {
        val reserve = (1..10).map { "g$it" }
        val old = System.currentTimeMillis() - AutoModePulse.ALIVE_TTL_MILLIS - 1_000
        val store = AutoModeStore().apply {
            reserveCount = 10
            reserveCheckedMillis = System.currentTimeMillis()
            aliveByGuid = reserve.associateWith { old }.toMutableMap()
        }
        assertFalse(AutoModePulse.isReserveHealthy(store, reserve.toSet()))
    }

    /** An empty reserve is not "healthy"; it is nothing at all. */
    @Test
    fun `an empty reserve is not healthy`() {
        val store = AutoModeStore().apply { reserveCheckedMillis = System.currentTimeMillis() }
        assertFalse(AutoModePulse.isReserveHealthy(store, emptySet()))
    }
}
