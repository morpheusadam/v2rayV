package com.v2ray.ang.automode

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * One download, measured. The only place throughput is ever measured.
 *
 * Auto Mode compares a server against the user's own line — "keep the first server that
 * reaches 70% of what this connection can do" — and that comparison is only meaningful if
 * both sides were measured the same way. They are not naturally the same: the accepted way
 * to measure a *line* is four to eight parallel streams, because a single TCP flow spends
 * too long in slow start and gives up too much to a single loss to fill a fast link, while
 * the honest way to measure a *proxy* is one stream, because one stream is what a user
 * actually gets. Measure the line in parallel and the servers serially and the ratio is
 * meaningless — nothing would ever pass 70%.
 *
 * So both go through here, single stream, same URL, same duration, same buffer. The
 * baseline is deliberately *not* the line's capacity. It is what one stream achieves on
 * this connection right now, which is the only thing a server can be asked to match.
 */
object ThroughputProbe {

    /** Large enough that the result is not dominated by TCP slow start. */
    const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=50000000"

    /** Stop reading here and divide by the time actually spent. */
    const val MAX_DOWNLOAD_MILLIS = 6_000L

    /** A connection that has not delivered a byte by now is not worth the rest of the budget. */
    const val FIRST_BYTE_TIMEOUT_MILLIS = 4_000

    private const val BUFFER_SIZE = 32 * 1024

    /** How often a running measurement reports what it has seen so far. */
    private const val SAMPLE_INTERVAL_MILLIS = 250L

    /**
     * Downloads for at most [MAX_DOWNLOAD_MILLIS] and reports MB/s over the time actually
     * spent reading, so a transfer that is cut off halfway is still scored on what it did
     * deliver rather than failed outright.
     *
     * @param proxy the loopback SOCKS inbound of a server under test, or null to measure
     *              this device's own connection.
     * @param onSample called every quarter second with the rate over that interval, so the
     *        dashboard can show a needle moving rather than freezing for six seconds and
     *        then printing a number. It is the instantaneous rate, not the running average
     *        the return value reports — a trace that smooths itself as it goes reads as a
     *        stuck meter.
     * @return MB/s, or 0.0 when nothing arrived.
     */
    fun measure(proxy: Proxy? = null, onSample: (Double) -> Unit = {}): Double {
        val builder = OkHttpClient.Builder()
            .connectTimeout(FIRST_BYTE_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(FIRST_BYTE_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(MAX_DOWNLOAD_MILLIS + FIRST_BYTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
        if (proxy != null) {
            builder.proxy(proxy)
        }
        val client = builder.build()

        val request = Request.Builder()
            .url(DOWNLOAD_URL)
            .get()
            .header("Connection", "close")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return 0.0
                }

                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                val started = System.nanoTime()
                val deadline = started + TimeUnit.MILLISECONDS.toNanos(MAX_DOWNLOAD_MILLIS)

                val sampleNanos = TimeUnit.MILLISECONDS.toNanos(SAMPLE_INTERVAL_MILLIS)
                var windowStart = started
                var windowBytes = 0L

                response.body.byteStream().use { input ->
                    while (System.nanoTime() < deadline) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        total += read
                        windowBytes += read

                        val now = System.nanoTime()
                        if (now - windowStart >= sampleNanos) {
                            val windowSeconds = (now - windowStart) / 1_000_000_000.0
                            onSample((windowBytes / 1_048_576.0) / windowSeconds)
                            windowStart = now
                            windowBytes = 0
                        }
                    }
                }

                val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0
                if (total <= 0 || elapsedSeconds <= 0) 0.0 else (total / 1_048_576.0) / elapsedSeconds
            }
        } catch (e: Exception) {
            LogUtil.d(AppConfig.TAG, "AutoMode: throughput probe failed: ${e.message}")
            0.0
        }
    }
}
