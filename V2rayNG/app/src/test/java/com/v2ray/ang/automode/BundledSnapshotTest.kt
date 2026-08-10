package com.v2ray.ang.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real parsers over the real files the APK ships.
 *
 * These two snapshots are copied by hand out of a repository this project does not build,
 * whose generator rewrites them daily and has already changed their shape once — bare
 * `ip:port` became `scheme://host:port | country | ms | age`, and a `# format:` line
 * appeared at the top. Nothing but a test connects the two sides.
 *
 * What makes this worth a test rather than a glance is where the snapshots are used: they
 * are the last rung of the route ladder, read only when a network has blocked every other
 * way of reaching the lists. If they stop parsing, the failure is silent, total, and lands
 * exactly on the users who have no alternative — and it would never show up in testing on
 * an open connection, because on an open connection these files are never opened.
 */
class BundledSnapshotTest {

    private val assets = File("src/main/assets")

    private fun asset(name: String): String {
        val file = File(assets, name)
        assertTrue("missing asset: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `the shipped proxy snapshot parses`() {
        val body = asset("automode_proxies.txt")
        val proxies = AutoModeProxy.parseList(body)

        // Every non-comment, non-blank line is meant to be a proxy. Losing even a tenth of
        // them to a format change would not fail anything else.
        val entries = body.lineSequence()
            .map { it.trim() }
            .count { it.isNotEmpty() && !it.startsWith("#") }

        assertEquals("every entry should parse", entries, proxies.size)
        assertTrue("expected a usable number of proxies, got ${proxies.size}", proxies.size >= 100)

        // Junk parses as junk long before it parses as nothing, so check the values landed
        // in the right fields rather than only that a list came back.
        assertTrue(proxies.all { it.port in 1..65535 })
        assertTrue(proxies.all { it.host.isNotBlank() && !it.host.contains(' ') && !it.host.contains('|') })
        assertTrue(proxies.none { it.host.contains("ms") })
    }

    /** The generator stamps what it publishes; this build was written against v1. */
    @Test
    fun `the shipped proxy snapshot declares the format this build knows`() {
        assertEquals(
            AutoModeProxy.SUPPORTED_FORMAT,
            AutoModeProxy.formatOf(asset("automode_proxies.txt"))
        )
    }

    /**
     * The subscription snapshot is a catalog of *links*, and is read by a different parser
     * from the one that reads servers. A comment header that started parsing as a URL would
     * add a source that can never work.
     */
    @Test
    fun `the shipped subscription snapshot parses as links`() {
        val links = AutoModeSourceManager.parseUrls(asset("automode_subs.txt"))

        assertTrue("expected a usable catalog, got ${links.size}", links.size >= 100)
        assertTrue(links.all { it.startsWith("http://") || it.startsWith("https://") })
        assertTrue("a comment leaked through", links.none { it.startsWith("#") })
        assertTrue("a header line leaked through", links.none { it.contains(' ') })
    }
}
