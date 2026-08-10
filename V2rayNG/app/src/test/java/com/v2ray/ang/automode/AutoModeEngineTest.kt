package com.v2ray.ang.automode

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Covers the decisions a run makes about what to keep. The stages themselves need a core
 * and a network; these are the pure choices sitting between them.
 */
class AutoModeEngineTest {

    // Never touched by the methods under test — they only read their arguments.
    private val engine = AutoModeEngine(mock(Context::class.java))

    private fun ref(
        guid: String,
        server: String = "1.2.3.4",
        port: String = "443",
        remarks: String = guid,
        type: EConfigType = EConfigType.VLESS,
        password: String? = "pw",
    ) = AutoModeEngine.ServerRef(
        guid,
        ProfileItem(
            configType = type,
            remarks = remarks,
            server = server,
            serverPort = port,
            password = password,
        )
    )

    private fun measured(
        guid: String,
        speed: Double,
        delay: Long,
        country: String? = null,
        remarks: String = guid,
    ) = AutoModeMeasurement(
        guid = guid,
        profile = ProfileItem(configType = EConfigType.VLESS, remarks = remarks, server = "1.2.3.4", serverPort = "443"),
        speedMbps = speed,
        delayMillis = delay,
        exitCountry = country,
    )

    @Test
    fun `the same endpoint from two sources only takes one slot`() {
        val merged = engine.mergeForSpeedTest(
            working = listOf(ref("a"), ref("b"), ref("c", server = "5.6.7.8")),
            champions = emptyList()
        )
        assertEquals(listOf("a", "c"), merged.map { it.guid })
    }

    /**
     * A champion is only ever displaced by losing the speed test, never by a run happening
     * to turn up a lot of new candidates first.
     */
    @Test
    fun `champions are not squeezed out by the newcomer cap`() {
        val champions = (1..8).map { ref("champ$it", server = "10.0.0.$it") }
        val working = (1..40).map { ref("new$it", server = "20.0.0.$it") }

        val merged = engine.mergeForSpeedTest(working, champions)

        assertTrue(merged.map { it.guid }.containsAll(champions.map { it.guid }))
        assertEquals(8 + 10, merged.size)
    }

    @Test
    fun `custom profiles and portless entries never reach the speed test`() {
        val merged = engine.mergeForSpeedTest(
            working = listOf(
                ref("custom", type = EConfigType.CUSTOM),
                ref("noport", port = "0", server = "9.9.9.9"),
                ref("ok", server = "8.8.8.8"),
            ),
            champions = emptyList()
        )
        assertEquals(listOf("ok"), merged.map { it.guid })
    }

    @Test
    fun `winners are ranked by speed and tie-broken by delay`() {
        val store = AutoModeStore(topCount = 3)
        val winners = engine.selectWinners(
            listOf(
                measured("slow", 0.4, 200),
                measured("fastest", 3.0, 900),
                measured("fast-tie-a", 1.0, 800),
                measured("fast-tie-b", 1.0, 100),
            ),
            store
        )
        assertEquals(listOf("fastest", "fast-tie-b", "fast-tie-a"), winners.map { it.guid })
    }

    @Test
    fun `a server that downloaded nothing or answered too slowly is not kept`() {
        val store = AutoModeStore(topCount = 5)
        val winners = engine.selectWinners(
            listOf(
                measured("nothing", 0.0, 100),
                measured("too-slow", 2.0, 9000),
                measured("untested", 2.0, -1),
                measured("good", 1.0, 300),
            ),
            store
        )
        assertEquals(listOf("good"), winners.map { it.guid })
    }

    @Test
    fun `the measured exit country outranks the label the provider wrote`() {
        val store = AutoModeStore(topCount = 1, countryFilter = mutableListOf("NL"))
        val winners = engine.selectWinners(
            listOf(
                // Claims the Netherlands, measured coming out of Germany.
                measured("liar", 5.0, 100, country = "DE", remarks = "🇳🇱 Netherlands"),
                measured("honest", 1.0, 100, country = "NL", remarks = "node 7"),
            ),
            store
        )
        assertEquals(listOf("honest"), winners.map { it.guid })
    }

    /** A half-empty list is worse than a full one topped up from elsewhere. */
    @Test
    fun `remaining slots are filled from outside the wanted country`() {
        val store = AutoModeStore(topCount = 3, countryFilter = mutableListOf("NL"))
        val winners = engine.selectWinners(
            listOf(
                measured("nl", 1.0, 100, country = "NL"),
                measured("de-fast", 9.0, 100, country = "DE"),
                measured("us", 2.0, 100, country = "US"),
            ),
            store
        )
        assertEquals(listOf("nl", "de-fast", "us"), winners.map { it.guid })
    }

    @Test
    fun `a label from a previous run is stripped before relabelling`() {
        assertEquals("Frankfurt 01", engine.stripRank("#2 1.4MB/s · 320ms · Frankfurt 01"))
    }

    @Test
    fun `a remark that merely looks like a label is left alone`() {
        assertEquals("#1 best server", engine.stripRank("#1 best server"))
        assertEquals("", engine.stripRank(null))
    }
}
