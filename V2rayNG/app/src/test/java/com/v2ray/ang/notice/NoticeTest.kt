package com.v2ray.ang.notice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slot is a channel from a text file straight onto the screen of an installed VPN app,
 * so what it *refuses* to show matters more than what it shows.
 */
class NoticeTest {

    private fun notice(
        id: String = "n1",
        title: String = "Update available",
        body: String = "",
        min: Int = 0,
        max: Int = 0,
    ) = Notice(id = id, title = title, body = body, minVersionCode = min, maxVersionCode = max)

    @Test
    fun `a notice with no id is never shown`() {
        assertFalse(notice(id = "").appliesTo(743))
    }

    @Test
    fun `a notice with neither title nor body is never shown`() {
        assertFalse(notice(title = "", body = "").appliesTo(743))
    }

    @Test
    fun `an unbounded notice applies to every version`() {
        assertTrue(notice().appliesTo(1))
        assertTrue(notice().appliesTo(999_999))
    }

    /**
     * The point of the upper bound: an "update available" card must stop appearing on the
     * version it was telling people to install, or it follows them there and never leaves.
     */
    @Test
    fun `an update notice stops applying once past its ceiling`() {
        val update = notice(max = 743)
        assertTrue(update.appliesTo(742))
        assertTrue(update.appliesTo(743))
        assertFalse(update.appliesTo(744))
    }

    @Test
    fun `a notice can be aimed at newer versions only`() {
        val only = notice(min = 700)
        assertFalse(only.appliesTo(699))
        assertTrue(only.appliesTo(700))
    }

    @Test
    fun `an action needs a label, https and a known type`() {
        assertTrue(NoticeAction("Update", "install", "https://github.com/u/r/releases/x.apk").isUsable)
        assertTrue(NoticeAction("Read", "url", "https://example.com").isUsable)

        assertFalse(NoticeAction("", "url", "https://example.com").isUsable)
        assertFalse(NoticeAction("Go", "url", "http://example.com").isUsable)
        assertFalse(NoticeAction("Go", "javascript", "https://example.com").isUsable)
    }

    /**
     * An APK is the one payload this app fetches and then runs, so where it may come from
     * is pinned rather than trusted to the document that names it.
     */
    @Test
    fun `only https github urls may be installed`() {
        assertTrue(NoticeInstaller.isTrustedUrl("https://github.com/morpheusadam/v2rayV/releases/download/v1/a.apk"))
        assertTrue(NoticeInstaller.isTrustedUrl("https://objects.github.com/a.apk"))

        assertFalse(NoticeInstaller.isTrustedUrl("http://github.com/a.apk"))
        assertFalse(NoticeInstaller.isTrustedUrl("https://evil.com/a.apk"))
        assertFalse(NoticeInstaller.isTrustedUrl("https://github.com.evil.com/a.apk"))
        assertFalse(NoticeInstaller.isTrustedUrl("not a url"))
    }
}
