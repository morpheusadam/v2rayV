package com.v2ray.ang.automode

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Tries several independent routes to the same file and takes whichever answers first.
 *
 * The routes handed to this are alternative hostnames serving one repository file, so they
 * fail independently: a block list that names `raw.githubusercontent.com` usually does not
 * name the CDNs too. Walking them one at a time therefore costs a full connect timeout for
 * every blocked one before the ladder can even report failure — with four routes and a six
 * second timeout, twenty-four seconds to learn that the host is blocked, and that price is
 * paid again by every fetch of every run.
 *
 * This is the problem [RFC 8305](https://www.rfc-editor.org/rfc/rfc8305) ("Happy Eyeballs
 * v2") solves for address families, and the same shape works here: start the next attempt
 * after a short fixed delay rather than after the previous one has failed.
 *
 * **The stagger matters as much as the race.** Firing all four at once would open three
 * useless sockets on every run on an open network, where the first route always wins. A
 * [STAGGER_MILLIS] head start means the later routes are never opened at all unless the
 * first one is genuinely slow — the fast path stays exactly as cheap as it is today, and
 * only the blocked path gets faster.
 */
object RouteRace {

    /**
     * How long a route gets to itself before the next one is allowed to start.
     *
     * RFC 8305 §5 recommends 250 ms for the equivalent delay, and reports it as the value
     * that keeps a working first choice from being raced unnecessarily while still hiding a
     * broken one behind a barely perceptible pause.
     */
    const val STAGGER_MILLIS = 250L

    /**
     * Runs [attempt] against each of [routes], staggered, and returns the first non-null
     * result. Null when every route failed.
     *
     * Losing attempts are cancelled, but not waited for. They are blocking socket reads,
     * which no amount of coroutine cancellation interrupts — the socket finishes on its own
     * timeout regardless. Waiting for them would give back everything the race just won, so
     * they are left to expire on their own; there are at most four, each already bounded by
     * the fetch timeout.
     */
    suspend fun <T : Any> first(
        routes: List<String>,
        attempt: suspend (String) -> T?,
    ): T? {
        if (routes.isEmpty()) {
            return null
        }
        // One route is not a race, and going through a scope would only add a thread hop.
        if (routes.size == 1) {
            return runAttempt(routes.first(), attempt)
        }

        val winner = CompletableDeferred<T?>()
        // Deliberately detached from the caller's job: see the note above about losers.
        // Cancelling the scope in the finally is what stops them being leaked past the race.
        val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())

        try {
            val attempts = routes.mapIndexed { index, route ->
                scope.launch {
                    delay(STAGGER_MILLIS * index)
                    runAttempt(route, attempt)?.let { winner.complete(it) }
                }
            }
            // The other way a race ends: nothing worked. Without this the caller would wait
            // on a Deferred that no attempt is ever going to complete.
            scope.launch {
                attempts.joinAll()
                winner.complete(null)
            }
            return winner.await()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun <T : Any> runAttempt(route: String, attempt: suspend (String) -> T?): T? =
        try {
            attempt(route)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.d(AppConfig.TAG, "AutoMode: route $route failed: ${e.message}")
            null
        }
}
