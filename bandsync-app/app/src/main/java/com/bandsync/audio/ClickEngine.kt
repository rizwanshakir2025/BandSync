package com.bandsync.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Synthesized metronome click on its own low-latency track.
 * Clicks are scheduled against the shared wall-clock timeline, so every
 * phone's downbeat lands together. Accent on beat 1 of each bar.
 */
class ClickEngine {
    private var track: AudioTrack? = null

    @Volatile
    private var stopFlag = false
    private var thread: Thread? = null

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var volume: Float = 0.8f

    private fun ensureTrack() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            WavIo.TARGET_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(WavIo.TARGET_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, WavIo.TARGET_RATE * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        try { track?.play() } catch (_: Exception) { }
    }

    private fun clickBuffer(accent: Boolean): ShortArray {
        val sr = WavIo.TARGET_RATE
        val n = (sr * 0.06).toInt()
        val freq = if (accent) 2000.0 else 1250.0
        val amp = if (accent) 0.9 else 0.6
        return ShortArray(n * 2) { i ->
            val t = (i / 2) / sr.toDouble()
            val v = sin(2 * PI * freq * t) * exp(-t * 70.0) * amp
            (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * @param firstClickWallMs wall-clock ms of the first click to emit
     *   (count-in start, or the first beat at/after the seek position)
     * @param totalClicks number of clicks, or -1 for infinite (loop mode)
     * @param startIndex beat index of the first click — accents stay glued
     *   to the song's downbeats when seeking into a playing timeline
     */
    fun start(
        firstClickWallMs: Long,
        bpm: Double,
        beatsPerBar: Int,
        totalClicks: Int,
        startIndex: Int = 0
    ) {
        stop()
        ensureTrack()
        stopFlag = false
        val beatMs = 60000.0 / bpm
        thread = Thread({
            var i = startIndex
            while (!stopFlag && (totalClicks < 0 || i < startIndex + totalClicks)) {
                val next = (firstClickWallMs + (i - startIndex) * beatMs).toLong()
                val wait = next - System.currentTimeMillis()
                if (wait > 10) {
                    try { Thread.sleep((wait - 5).coerceAtMost(40)) } catch (_: InterruptedException) { break }
                    continue
                }
                if (wait <= 0) {
                    if (enabled) {
                        try {
                            track?.setVolume(volume, volume)
                            val buf = clickBuffer(i % beatsPerBar == 0)
                            track?.write(buf, 0, buf.size)
                        } catch (_: Exception) {
                            break
                        }
                    }
                    i++
                }
            }
        }, "bandsync-click").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopFlag = true
        try { thread?.join(400) } catch (_: Exception) { }
        thread = null
    }

    fun release() {
        stop()
        try { track?.stop(); track?.release() } catch (_: Exception) { }
        track = null
    }
}
