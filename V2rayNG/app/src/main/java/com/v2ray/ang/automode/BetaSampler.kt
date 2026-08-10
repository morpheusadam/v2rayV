package com.v2ray.ang.automode

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Draws from a Beta distribution, which is what lets Auto Mode balance "use the source
 * that has been working" against "give the untested source a chance". A source with
 * little evidence has a wide posterior and so occasionally wins a draw; one with a long
 * record of failure narrows toward zero and stops being picked.
 */
object BetaSampler {

    /** Beta(alpha, beta) via two Gamma draws — no distribution library needed. */
    fun sample(alpha: Double, beta: Double): Double {
        val x = sampleGamma(max(0.05, alpha))
        val y = sampleGamma(max(0.05, beta))
        return if (x + y <= 0) 0.0 else x / (x + y)
    }

    /** Marsaglia-Tsang gamma sampler, with the standard boost for shape < 1. */
    fun sampleGamma(shape: Double): Double {
        if (shape < 1) {
            return sampleGamma(shape + 1) * nextDouble().pow(1.0 / shape)
        }

        val d = shape - (1.0 / 3.0)
        val c = 1.0 / sqrt(9 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = nextGaussian()
                v = 1 + (c * x)
            } while (v <= 0)

            v = v * v * v
            val u = nextDouble()
            if (u < 1 - (0.0331 * x * x * x * x)) {
                return d * v
            }
            if (ln(u) < (0.5 * x * x) + (d * (1 - v + ln(v)))) {
                return d * v
            }
        }
    }

    /** Never exactly zero, which would blow up the logarithms above. */
    private fun nextDouble(): Double = max(1e-12, ThreadLocalRandom.current().nextDouble())

    private fun nextGaussian(): Double {
        val u1 = nextDouble()
        val u2 = nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}
