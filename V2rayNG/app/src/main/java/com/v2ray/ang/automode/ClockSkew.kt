package com.v2ray.ang.automode

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * How far the device's clock is from real time, learned for free from responses the app was
 * already making.
 *
 * This matters more than it sounds. VMess and VLESS authenticate with a timestamp, and the
 * server rejects anything outside a window of a couple of minutes. A phone whose clock has
 * drifted therefore fails to connect to *every* server, with no error that points at the
 * cause: the tunnel comes up, the handshake is refused, and the app reports what looks like
 * a few hundred dead servers. Users conclude the app is broken, and they are not wrong to,
 * because nothing they can see says otherwise.
 *
 * It is a common enough failure to be worth naming out loud, and free to detect: every HTTP
 * response carries a Date header, so the answer arrives with traffic that had to happen
 * anyway. No extra request, no extra host contacted.
 */
object ClockSkew {

    /**
     * Beyond this, connections start being refused. The usual VMess window is 120 seconds
     * either side, so this warns while there is still margin rather than once everything has
     * already stopped working.
     */
    private const val WARN_SECONDS = 90L

    /** Ignore absurd readings: a proxy rewriting Date badly should not raise a false alarm. */
    private const val IMPLAUSIBLE_SECONDS = 10L * 365 * 24 * 60 * 60

    @Volatile
    private var lastSkewSeconds: Long? = null

    /** RFC 7231 preferred format, which is what Date headers use, always in GMT. */
    private val format: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
    }

    /**
     * Records the skew implied by one response's headers. Cheap, and safe to call on every
     * response: a header that will not parse is simply ignored.
     */
    fun observe(headers: Map<String, String>) {
        val date = headers["date"]?.takeIf { it.isNotBlank() } ?: return
        val parsed = try {
            format.get()?.parse(date) ?: return
        } catch (e: Exception) {
            return
        }

        val skew = (System.currentTimeMillis() - parsed.time) / 1000
        if (abs(skew) > IMPLAUSIBLE_SECONDS) {
            return
        }
        lastSkewSeconds = skew

        if (abs(skew) > WARN_SECONDS) {
            LogUtil.w(
                AppConfig.TAG,
                "ClockSkew: device clock is ${skew}s from server time, VMess and VLESS will be refused"
            )
        }
    }

    /** Seconds the device clock is ahead of real time, negative when behind, null if unknown. */
    fun skewSeconds(): Long? = lastSkewSeconds

    /** True when the clock is far enough out to be the reason nothing connects. */
    fun isSkewed(): Boolean = lastSkewSeconds?.let { abs(it) > WARN_SECONDS } == true

    /** What the device thinks the time is, for a message that has to be acted on by a human. */
    fun deviceTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    /** Forgets the reading, so a run does not report a skew measured before the clock was fixed. */
    fun reset() {
        lastSkewSeconds = null
    }
}
