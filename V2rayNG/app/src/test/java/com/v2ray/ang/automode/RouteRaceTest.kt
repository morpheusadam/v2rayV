package com.v2ray.ang.automode

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The race exists to stop a blocked first route from costing a full connect timeout before
 * the second one is even tried, so what these check is the timing behaviour rather than the
 * return value alone.
 */
class RouteRaceTest {

    private val fourRoutes = listOf("direct", "jsdelivr", "githack", "gitcdn")

    @Test
    fun `the first route that answers wins`() = runBlocking {
        val winner = RouteRace.first(fourRoutes) { route ->
            route.takeIf { it == "direct" }
        }
        assertEquals("direct", winner)
    }

    /**
     * The whole point: four dead routes at six seconds each must not cost twenty-four
     * seconds. Simulated with a delay longer than the stagger so the arithmetic is the same
     * shape as the real one without the test taking that long.
     */
    @Test
    fun `a blocked first route does not hold up the rest`() = runBlocking {
        val started = System.currentTimeMillis()

        val winner = RouteRace.first(fourRoutes) { route ->
            if (route == "gitcdn") {
                route
            } else {
                delay(2_000)   // stands in for a connect timeout
                null
            }
        }

        val elapsed = System.currentTimeMillis() - started
        assertEquals("gitcdn", winner)
        // Sequentially this would be 6s of timeouts before gitcdn is even tried. Raced, the
        // winner is reached after three staggers.
        assertTrue("raced in ${elapsed}ms, expected well under a sequential walk", elapsed < 1_500)
    }

    /**
     * The stagger is not an implementation detail — without it every run on an open network
     * would open three sockets it never needed.
     */
    @Test
    fun `a route that answers at once means the later ones are never opened`() = runBlocking {
        val opened = ConcurrentLinkedQueue<String>()

        val winner = RouteRace.first(fourRoutes) { route ->
            opened.add(route)
            route.takeIf { it == "direct" }
        }

        assertEquals("direct", winner)
        assertEquals(listOf("direct"), opened.toList())
    }

    @Test
    fun `every route failing returns null rather than hanging`() = runBlocking {
        val attempts = AtomicInteger()

        val winner = RouteRace.first(fourRoutes) { _ ->
            attempts.incrementAndGet()
            null
        }

        assertNull(winner)
        assertEquals(4, attempts.get())
    }

    @Test
    fun `a route that throws is treated as a failure, not an error`() = runBlocking {
        val winner = RouteRace.first(fourRoutes) { route ->
            if (route == "direct") throw IllegalStateException("blocked") else route
        }
        assertEquals("jsdelivr", winner)
    }

    @Test
    fun `an empty ladder is null and a single route skips the race`() = runBlocking {
        assertNull(RouteRace.first(emptyList<String>()) { it })
        assertEquals("only", RouteRace.first(listOf("only")) { it })
    }
}
