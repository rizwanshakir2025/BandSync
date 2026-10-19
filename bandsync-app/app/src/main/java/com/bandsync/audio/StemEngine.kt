package com.bandsync.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import java.io.File

/**
 * One AudioTrack per stem, all 48 kHz stereo. Each track's playback speed
 * is set to targetBpm/nativeBpm so stems follow the host tempo with
 * pitch preserved (platform Sonic stretcher). Per-stem balance is the
 * track volume — entirely local to this phone.
 */
class StemEngine {
    private class Stem(val meta: StemMeta, val pcm: WavIo.Pcm, val track: AudioTrack)

    private var stems: List<Stem> = emptyList()

    @Volatile
    private var stopFlag = false
    private var threads: List<Thread> = emptyList()

    val stemNames: List<String> get() = stems.map { it.meta.name }

    /** Total stem length in beats at the song's native tempo. */
    fun songBeats(nativeBpm: Double): Double {
        val frames = stems.firstOrNull()?.pcm?.frames ?: 0
        if (frames == 0) return 0.0
        return frames.toDouble() / WavIo.TARGET_RATE / 60.0 * nativeBpm
    }

    fun load(song: Song) {
        release()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(WavIo.TARGET_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(
            WavIo.TARGET_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        stems = song.stems.map { meta ->
            val pcm = WavIo.readWav(File(song.dir, meta.file))
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, WavIo.TARGET_RATE / 5 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track.setVolume(1.0f)
            Stem(meta, pcm, track)
        }
    }

    fun setVolume(stemName: String, v: Float) {
        stems.find { it.meta.name == stemName }?.let {
            try { it.track.setVolume(v) } catch (_: Exception) { }
        }
    }

    /**
     * Start all stems together at [startWallMs] (wall-clock ms).
     * Feeder threads wake within ~1–3 ms of each other — inaudible for stems.
     * @param startFrame seek position in 48 kHz frames (for joining a
     *   timeline that is already playing, e.g. from the PC/DAW host).
     */
    fun play(startWallMs: Long, speedRatio: Float, loop: Boolean, startFrame: Int = 0) {
        stop()
        stopFlag = false
        threads = stems.map { stem ->
            Thread({
                try {
                    stem.track.playbackParams =
                        PlaybackParams().setSpeed(speedRatio.coerceIn(0.25f, 4.0f))
                } catch (_: Exception) {
                    // device ignored speed — stems play at native tempo
                }
                while (!stopFlag) {
                    val wait = startWallMs - System.currentTimeMillis()
                    if (wait <= 0) break
                    try { Thread.sleep(wait.coerceAtMost(50)) } catch (_: InterruptedException) { return@Thread }
                }
                if (!stopFlag) feed(stem, loop, startFrame)
            }, "bandsync-stem").apply { isDaemon = true; start() }
        }
    }

    private fun feed(stem: Stem, loop: Boolean, startFrame: Int) {
        val track = stem.track
        val pcm = stem.pcm.samples
        val frames = stem.pcm.frames
        val chunkFrames = 2048
        val out = ShortArray(chunkFrames * 2)
        var pos = startFrame.coerceIn(0, frames)
        try { track.play() } catch (_: Exception) { return }
        while (!stopFlag && (loop || pos < frames)) {
            if (pos >= frames) pos = 0
            val n = minOf(chunkFrames, frames - pos)
            for (i in 0 until n) {
                val l = (pcm[(pos + i) * 2] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                val r = (pcm[(pos + i) * 2 + 1] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                out[i * 2] = l
                out[i * 2 + 1] = r
            }
            try {
                track.write(out, 0, n * 2)
            } catch (_: Exception) {
                break
            }
            pos += n
        }
        try { track.pause(); track.flush() } catch (_: Exception) { }
    }

    /**
     * Tempo-map playback (PC/DAW host). The stretch ratio follows the map:
     * speed flips exactly on the precomputed boundary frames, so tempo
     * changes land on the musical section boundary with no re-seek.
     * Loop is always false with a map (the DAW owns the timeline).
     */
    fun playMapped(
        startWallMs: Long,
        nativeBpm: Double,
        tempoMap: com.bandsync.model.TempoMap,
        startFrame: Int,
        startBeat: Double
    ) {
        stop()
        stopFlag = false
        threads = stems.map { stem ->
            // each stem gets its own copy of the switch schedule
            val switches = tempoMap.switchesAfter(startBeat, nativeBpm).toMutableList()
            Thread({
                val initRatio =
                    (tempoMap.bpmAt(startBeat) / nativeBpm).toFloat().coerceIn(0.25f, 4.0f)
                try {
                    stem.track.playbackParams = PlaybackParams().setSpeed(initRatio)
                } catch (_: Exception) {
                    // device ignored speed — stems play at native tempo
                }
                while (!stopFlag) {
                    val wait = startWallMs - System.currentTimeMillis()
                    if (wait <= 0) break
                    try { Thread.sleep(wait.coerceAtMost(50)) } catch (_: InterruptedException) { return@Thread }
                }
                if (!stopFlag) feedMapped(stem, switches, startFrame)
            }, "bandsync-stem").apply { isDaemon = true; start() }
        }
    }

    private fun feedMapped(
        stem: Stem,
        switches: MutableList<Pair<Long, Float>>,
        startFrame: Int
    ) {
        val track = stem.track
        val pcm = stem.pcm.samples
        val frames = stem.pcm.frames
        val chunkFrames = 2048
        val out = ShortArray(chunkFrames * 2)
        var pos = startFrame.coerceIn(0, frames)
        try { track.play() } catch (_: Exception) { return }
        while (!stopFlag && pos < frames) {
            while (switches.isNotEmpty() && switches[0].first <= pos) {
                val ratio = switches.removeAt(0).second.coerceIn(0.25f, 4.0f)
                try {
                    track.playbackParams = PlaybackParams().setSpeed(ratio)
                } catch (_: Exception) { }
            }
            val n = minOf(chunkFrames, frames - pos)
            for (i in 0 until n) {
                val l = (pcm[(pos + i) * 2] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                val r = (pcm[(pos + i) * 2 + 1] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                out[i * 2] = l
                out[i * 2 + 1] = r
            }
            try {
                track.write(out, 0, n * 2)
            } catch (_: Exception) {
                break
            }
            pos += n
        }
        try { track.pause(); track.flush() } catch (_: Exception) { }
    }

    fun stop() {
        stopFlag = true
        threads.forEach { try { it.join(400) } catch (_: Exception) { } }
        threads = emptyList()
        stems.forEach { try { it.track.pause(); it.track.flush() } catch (_: Exception) { } }
    }

    fun release() {
        stop()
        stems.forEach { try { it.track.release() } catch (_: Exception) { } }
        stems = emptyList()
    }
}
