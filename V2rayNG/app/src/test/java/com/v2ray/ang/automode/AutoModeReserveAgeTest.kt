package com.v2ray.ang.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * When the reserve is old enough to be worth rebuilding.
 *
 * This is the rule behind the report that the app "worked for the first few days". The
 * background refresh used to decide by counting the reserve, and the count never changes:
 * a run writes ten servers and nothing removes one when it dies, so ten dead servers still
 * counted as ten and every scheduled refresh after the first was declined for good. Age is
 * the only part of a reserve that actually moves on its own.
 */
class AutoModeReserveAgeTest {

    private fun storeBuilt(agoMillis: Long) = AutoModeStore(
        reserveBuiltMillis = System.currentTimeMillis() - agoMillis
    )

    @Test
    fun `a reserve that was never built is as old as it gets`() {
        assertEquals(Long.MAX_VALUE, AutoModeScheduler.reserveAgeMillis(AutoModeStore()))
    }

    @Test
    fun `age is measured from when the reserve was built`() {
        val age = AutoModeScheduler.reserveAgeMillis(storeBuilt(TimeUnit.HOURS.toMillis(5)))
        assertTrue("expected about five hours, got $age", age in TimeUnit.HOURS.toMillis(5)..TimeUnit.HOURS.toMillis(6))
    }

    /**
     * A run that starts is not a reserve that was replaced. Timing off the start of the
     * last run would let a week of failed runs keep the schedule quiet while the servers
     * they failed to replace went on dying.
     */
    @Test
    fun `a run that started but never finished does not make the reserve look fresh`() {
        val store = AutoModeStore(lastRunMillis = System.currentTimeMillis())
        assertEquals(Long.MAX_VALUE, AutoModeScheduler.reserveAgeMillis(store))
    }

    /**
     * A phone whose clock jumps backwards must not end up holding a reserve that reports
     * itself as built in the future, which would be negative age and never due again.
     */
    @Test
    fun `a clock moved backwards cannot freeze the schedule`() {
        val store = AutoModeStore(reserveBuiltMillis = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30))
        assertEquals(0L, AutoModeScheduler.reserveAgeMillis(store))
    }
}
