package com.bandsync.sync

/**
 * NTP-style clock offset estimator (client side).
 *
 * For a ping/pong exchange:
 *   t1 = client send time, t2 = host receive time,
 *   t3 = host send time,   t4 = client receive time
 * offset (hostClock - clientClock) = ((t2 - t1) + (t3 - t4)) / 2
 *
 * Samples with a congested round-trip are discarded and the running
 * estimate is the median of the recent window, which rejects Wi-Fi
 * jitter spikes without any tuning.
 */
class ClockSync {
    private val window = ArrayDeque<Double>()
    private var lastRtt: Long = 0L
    private val maxSamples = 12
    private val maxRttMs = 120L

    @Synchronized
    fun addSample(t1: Long, t2: Long, t3: Long, t4: Long) {
        val rtt = (t4 - t1) - (t3 - t2)
        if (rtt < 0 || rtt > maxRttMs) return // congested sample, ignore
        lastRtt = rtt
        val offset = ((t2 - t1) + (t3 - t4)) / 2.0
        if (window.size >= maxSamples) window.removeFirst()
        window.addLast(offset)
    }

    /** Median host-minus-client offset in ms. 0 when no samples yet. */
    @Synchronized
    fun offsetMs(): Double {
        if (window.isEmpty()) return 0.0
        val sorted = window.sorted()
        return sorted[sorted.size / 2]
    }

    /** Mean absolute deviation of the window — a jitter readout for the UI. */
    @Synchronized
    fun jitterMs(): Double {
        if (window.size < 2) return 0.0
        val med = offsetMs()
        return window.map { kotlin.math.abs(it - med) }.average()
    }

    @Synchronized fun sampleCount(): Int = window.size
    @Synchronized fun lastRttMs(): Long = lastRtt

    /** Convert a host wall-clock timestamp to this device's wall clock. */
    fun hostToLocal(hostMs: Long): Long = (hostMs - offsetMs()).toLong()
}
