package com.v2ray.ang.automode

import com.v2ray.ang.handler.MmkvManager

/**
 * The list of ready servers, and moving through it.
 *
 * The reserve exists so that "this one is no good" costs a tap rather than a minute. A run
 * leaves ten servers behind, ordered best first; [next] hands out the one after whichever
 * is selected, and reports when it has run out so the caller can go and find more.
 *
 * Wrapping is deliberately *not* done. A user who has worked through all ten and is still
 * unhappy has learned something — this batch is bad — and quietly handing back the first
 * one again would hide that and leave them tapping in a circle.
 */
object AutoModeReserve {

    /** Servers currently held, best first, skipping any whose profile has gone. */
    fun servers(): List<String> =
        MmkvManager.decodeServerList(AutoModeEngine.TOP_GROUP_ID)
            .filter { MmkvManager.decodeServerConfig(it) != null }

    fun size(): Int = servers().size

    /**
     * The position of [guid] in the reserve, or -1 when it is not in it — which is the
     * normal case for a server the user picked by hand from the full list.
     */
    fun indexOf(guid: String?): Int = if (guid == null) -1 else servers().indexOf(guid)

    sealed interface Next {
        /** Switch to this one. */
        data class Server(val guid: String, val position: Int, val total: Int) : Next

        /** Nothing left worth trying; the caller should run a fresh search. */
        data object Exhausted : Next
    }

    /**
     * The next server after [currentGuid].
     *
     * A selection that is not in the reserve at all starts from the top rather than being
     * treated as exhausted: the user is on something they chose by hand, and "next" should
     * mean "the best one Auto Mode found", not "no more".
     */
    fun next(currentGuid: String?): Next {
        val list = servers()
        if (list.isEmpty()) {
            return Next.Exhausted
        }

        val current = if (currentGuid == null) -1 else list.indexOf(currentGuid)
        val candidate = current + 1
        if (candidate >= list.size) {
            return Next.Exhausted
        }
        return Next.Server(list[candidate], candidate + 1, list.size)
    }
}
