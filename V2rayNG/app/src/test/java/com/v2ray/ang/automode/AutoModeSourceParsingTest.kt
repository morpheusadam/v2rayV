package com.v2ray.ang.automode

import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModeSourceParsingTest {

    @Test
    fun `pulls links out of the mess a real list arrives in`() {
        val text = """
            Pack 3: https://example.com/a.txt
            not a link at all
            https://example.com/b.txt, https://example.com/c.txt
        """.trimIndent()

        assertEquals(
            listOf(
                "https://example.com/a.txt",
                "https://example.com/b.txt",
                "https://example.com/c.txt",
            ),
            AutoModeSourceManager.parseUrls(text)
        )
    }

    @Test
    fun `rewrites a github blob link to its raw host`() {
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/sub.txt",
            AutoModeSourceManager.normalizeUrl("https://github.com/user/repo/blob/main/sub.txt")
        )
    }

    /**
     * A template row must be rejected whole. Truncating it at the angle bracket would
     * leave a valid-looking directory URL that fetches nothing useful every run.
     */
    @Test
    fun `rejects placeholder rows rather than truncating them`() {
        assertNull(AutoModeSourceManager.normalizeUrl("https://example.com/countries/<CODE>.sub.txt"))
        assertNull(AutoModeSourceManager.normalizeUrl("https://github.com/YOUR_USERNAME/repo/raw/main/sub"))
        assertTrue(
            AutoModeSourceManager.parseUrls("https://example.com/countries/<CODE>.sub.txt").isEmpty()
        )
    }

    @Test
    fun `strips trailing punctuation picked up from prose`() {
        assertEquals(
            "https://example.com/a.txt",
            AutoModeSourceManager.normalizeUrl("https://example.com/a.txt).")
        )
    }

    @Test
    fun `non http schemes are not sources`() {
        assertNull(AutoModeSourceManager.normalizeUrl("vless://abc@1.2.3.4:443"))
        assertNull(AutoModeSourceManager.normalizeUrl("ftp://example.com/a.txt"))
        assertNull(AutoModeSourceManager.normalizeUrl(""))
        assertNull(AutoModeSourceManager.normalizeUrl(null))
    }

    @Test
    fun `protocol aliases map onto the enum`() {
        assertEquals(
            listOf(EConfigType.SHADOWSOCKS.name, EConfigType.HYSTERIA2.name, EConfigType.VLESS.name),
            AutoModeSourceManager.parseProtocols("ss, hy2 vless")
        )
    }

    @Test
    fun `an unknown protocol word narrows nothing`() {
        assertTrue(AutoModeSourceManager.parseProtocols("smoke signals").isEmpty())
    }

    @Test
    fun `a fetch failure penalises the source and counts toward parking`() {
        val source = AutoModeSource(url = "https://example.com/a.txt")
        AutoModeSourceManager.applyResult(source, 0, 0, 0, fetchFailed = true, hash = null, configCount = 0, bestSpeedMbps = 0.0)

        assertEquals(1, source.tried)
        assertEquals(1, source.deadStreak)
        assertEquals(3.0, source.beta, 1e-9)
        assertEquals(1.0, source.alpha, 1e-9)
    }

    @Test
    fun `working servers raise the score and winners count double`() {
        val source = AutoModeSource(url = "https://example.com/a.txt")
        AutoModeSourceManager.applyResult(source, 10, 4, 2, fetchFailed = false, hash = "h1", configCount = 300, bestSpeedMbps = 1.5)

        assertEquals(1.0 + 4 + 4, source.alpha, 1e-9)
        assertEquals(1.0 + 6, source.beta, 1e-9)
        assertEquals(0, source.deadStreak)
        assertEquals(300, source.lastConfigCount)
        assertEquals(1.5, source.bestSpeedMbps, 1e-9)
    }

    @Test
    fun `identical content across runs is recorded as stale`() {
        val source = AutoModeSource(url = "https://example.com/a.txt")
        AutoModeSourceManager.applyResult(source, 1, 1, 0, false, "same", 10, 0.0)
        AutoModeSourceManager.applyResult(source, 1, 1, 0, false, "same", 10, 0.0)
        assertEquals(1, source.staleRuns)

        AutoModeSourceManager.applyResult(source, 1, 1, 0, false, "different", 10, 0.0)
        assertEquals(0, source.staleRuns)
    }

    @Test
    fun `five empty runs park a source, and one good run brings it back`() {
        val source = AutoModeSource(url = "https://example.com/a.txt")
        repeat(5) {
            AutoModeSourceManager.applyResult(source, 20, 0, 0, false, "h$it", 100, 0.0)
        }
        assertFalse(source.enabled)
        assertTrue(source.autoDisabled)

        AutoModeSourceManager.applyResult(source, 20, 3, 0, false, "later", 100, 0.0)
        assertTrue(source.enabled)
        assertFalse(source.autoDisabled)
    }

    @Test
    fun `a short run widens the net and a comfortable one narrows it`() {
        val store = AutoModeStore(sourcesPerRun = 8)

        AutoModeSourceManager.adaptSourceCount(store, passers = 2, target = 12)
        assertEquals(12, store.sourcesPerRun)

        AutoModeSourceManager.adaptSourceCount(store, passers = 20, target = 12)
        assertEquals(10, store.sourcesPerRun)
    }

    @Test
    fun `source count stays inside its bounds however lopsided the runs are`() {
        val store = AutoModeStore(sourcesPerRun = 8)
        repeat(20) { AutoModeSourceManager.adaptSourceCount(store, passers = 0, target = 12) }
        assertEquals(20, store.sourcesPerRun)

        repeat(20) { AutoModeSourceManager.adaptSourceCount(store, passers = 99, target = 12) }
        assertEquals(4, store.sourcesPerRun)
    }
}
