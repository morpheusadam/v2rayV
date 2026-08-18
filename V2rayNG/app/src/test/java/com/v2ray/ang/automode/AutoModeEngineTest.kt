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

    /**
     * The labels are what the end-of-run timing line is read by — "line 8.1s · fetch 7.6s · …"
     * — and a screenshot of that line is the only evidence a censored network will produce.
     * Two stages sharing a label would silently merge into one entry.
     */
    @Test
    fun `every stage has a distinct short label for the timing line`() {
        val labels = AutoModeStage.entries.map { it.label }

        assertEquals(labels.size, labels.distinct().size)
        assertTrue(labels.none { it.isBlank() })
    }

    /** Nothing ran, so there is nothing to report — not a line of zeroes. */
    @Test
    fun `the timing line is empty before any stage has run`() {
        assertEquals("", engine.timingLine())
    }

    /**
     * Asserted as a set, not a sequence: newcomers go through [AutoModeRanker.prioritise],
     * which shuffles within a tier on purpose, and "a" and "b" are the same endpoint at
     * the same tier. Which of the two survives is a coin toss and does not matter — that
     * one of them does, and that "c" is not crowded out, is the whole claim.
     */
    @Test
    fun `the same endpoint from two sources only takes one slot`() {
        val merged = engine.mergeForSpeedTest(
            working = listOf(ref("a"), ref("b"), ref("c", server = "5.6.7.8")),
            champions = emptyList()
        )
        val guids = merged.map { it.guid }
        assertEquals(2, guids.size)
        assertTrue("expected exactly one of a/b, got $guids", guids.count { it == "a" || it == "b" } == 1)
        assertTrue("expected c to keep its slot, got $guids", guids.contains("c"))
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
    fun `a label is built from what was measured, never from the provider's remark`() {
        val advert = measured("nl", 1.4, 320, remarks = "🇳🇱 JOIN @somechannel — permanent connection")
        val label = engine.label(1, advert, "NL")

        assertEquals("#2 1.4MB/s · 320ms · NL", label)
        assertTrue("the provider's text must not survive", !label.contains("JOIN"))
    }

    @Test
    fun `a label leaves the country out rather than inventing one`() {
        val unknown = measured("x", 2.1, 180)
        assertEquals("#3 2.1MB/s · 180ms", engine.label(2, unknown, null))
        assertEquals("#3 2.1MB/s · 180ms", engine.label(2, unknown, ""))
    }

    /**
     * The one behaviour Iran mode exists to remove. A country *filter* tops its list up
     * with the fastest servers found anywhere, which for a user trying to reach an Iranian
     * bank is a connection that looks like it worked and cannot do the job.
     */
    @Test
    fun `iran mode leaves the slots empty rather than filling them from abroad`() {
        val store = AutoModeStore(topCount = 3, iranMode = true)
        val winners = engine.selectWinners(
            listOf(
                measured("ir", 0.6, 400, country = "IR"),
                measured("de-fast", 9.0, 100, country = "DE"),
                measured("us-fast", 8.0, 100, country = "US"),
            ),
            store
        )
        assertEquals(listOf("ir"), winners.map { it.guid })
    }

    /** And it can come back with nothing at all, which the run then reports as nothing. */
    @Test
    fun `iran mode keeps nothing when nothing came out of iran`() {
        val store = AutoModeStore(topCount = 3, iranMode = true)
        val winners = engine.selectWinners(
            listOf(
                measured("de", 9.0, 100, country = "DE"),
                measured("nl", 8.0, 100, country = "NL"),
            ),
            store
        )
        assertTrue(winners.isEmpty())
    }

    /** Speed still orders the Iranian servers against each other. */
    @Test
    fun `the fastest iranian server is still the one ranked first`() {
        val store = AutoModeStore(topCount = 2, iranMode = true)
        val winners = engine.selectWinners(
            listOf(
                measured("slow", 0.3, 500, country = "IR"),
                measured("fast", 2.0, 500, country = "IR"),
            ),
            store
        )
        assertEquals(listOf("fast", "slow"), winners.map { it.guid })
    }

    /**
     * The mid-run connect is the same decision made earlier, so it needs the same guard:
     * without it, a run would hand the user a German server seconds in and only correct
     * itself at the end.
     */
    @Test
    fun `iran mode does not accept a foreign server mid-run`() {
        val german = measured("de", 9.0, 100, country = "DE")
        assertTrue(engine.isAcceptable(german, 1.0, iranMode = false))
        assertFalse(engine.isAcceptable(german, 1.0, iranMode = true))
        assertTrue(engine.isAcceptable(measured("ir", 1.5, 400, country = "IR"), 1.0, iranMode = true))
    }
}
