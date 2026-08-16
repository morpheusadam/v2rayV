package com.v2ray.ang.automode

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The clock check exists to explain a failure that otherwise looks like "every server is
 * dead", so the cases that matter are the ones where it must stay quiet and the ones where
 * it must speak up.
 */
class ClockSkewTest {

    @After
    fun tearDown() = ClockSkew.reset()

    private fun dateHeader(offsetSeconds: Long): Map<String, String> {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        return mapOf("date" to format.format(Date(System.currentTimeMillis() - offsetSeconds * 1000)))
    }

    @Test
    fun `a correct clock is not reported as skewed`() {
        ClockSkew.observe(dateHeader(0))
        assertFalse(ClockSkew.isSkewed())
    }

    @Test
    fun `a few seconds of drift is not worth mentioning`() {
        // Round trip time alone accounts for this. Warning here would cry wolf on every run.
        ClockSkew.observe(dateHeader(20))
        assertFalse(ClockSkew.isSkewed())
    }

    @Test
    fun `a clock far enough out to break handshakes is reported`() {
        ClockSkew.observe(dateHeader(600))
        assertTrue(ClockSkew.isSkewed())
        assertTrue((ClockSkew.skewSeconds() ?: 0) > 0)
    }

    /** A clock behind real time breaks handshakes exactly as badly as one ahead of it. */
    @Test
    fun `a clock running slow is reported too`() {
        ClockSkew.observe(dateHeader(-600))
        assertTrue(ClockSkew.isSkewed())
        assertTrue((ClockSkew.skewSeconds() ?: 0) < 0)
    }

    @Test
    fun `a response with no usable date teaches it nothing`() {
        ClockSkew.observe(emptyMap())
        assertNull(ClockSkew.skewSeconds())

        ClockSkew.observe(mapOf("date" to "not a date at all"))
        assertNull(ClockSkew.skewSeconds())

        ClockSkew.observe(mapOf("date" to ""))
        assertNull(ClockSkew.skewSeconds())
    }

    /**
     * A proxy that rewrites Date badly should not convince the app the user's clock is out
     * by a decade and hand them a confident, useless explanation.
     */
    @Test
    fun `an absurd reading is ignored rather than believed`() {
        ClockSkew.observe(dateHeader(0))
        ClockSkew.observe(mapOf("date" to "Thu, 01 Jan 1970 00:00:00 GMT"))

        assertFalse(ClockSkew.isSkewed())
    }
}
