package com.bandsync.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Minimal WAV reader/writer. v1 supports PCM 16-bit, 8-bit, 24-bit and
 * 32-bit float, mono or stereo, any sample rate. Everything is converted
 * to interleaved stereo float at 48 kHz for the playback engine.
 */
object WavIo {
    const val TARGET_RATE = 48000

    data class Pcm(val samples: FloatArray, val frames: Int)

    fun readWav(file: File): Pcm {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "Too small to be WAV: ${file.name}" }
        require(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
            "Not a WAV file: ${file.name}"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12)
        var audioFormat = 1
        var channels = 2
        var sampleRate = 44100
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        while (buf.remaining() >= 8) {
            val idBytes = ByteArray(4)
            buf.get(idBytes)
            val id = idBytes.toString(Charsets.US_ASCII)
            val size = buf.int
            val chunkStart = buf.position()
            when (id) {
                "fmt " -> {
                    audioFormat = buf.short.toInt() and 0xFFFF
                    channels = buf.short.toInt() and 0xFFFF
                    sampleRate = buf.int
                    buf.int // byteRate
                    buf.short // blockAlign
                    bits = buf.short.toInt() and 0xFFFF
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = size
                }
            }
            buf.position(chunkStart + size)
        }
        require(dataOffset >= 0) { "WAV has no data chunk: ${file.name}" }
        require(channels in 1..2) { "Only mono/stereo supported: ${file.name}" }

        val bytesPerSample = bits / 8
        val frameCount = dataSize / (bytesPerSample * channels)
        val d = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        val interleaved = FloatArray(frameCount * channels)
        var p = 0
        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                val v: Double = when {
                    audioFormat == 3 && bits == 32 -> d.float.toDouble()
                    bits == 16 -> d.short / 32768.0
                    bits == 8 -> (d.get().toInt() and 0xFF - 128) / 128.0
                    bits == 24 -> {
                        val b0 = d.get().toInt() and 0xFF
                        val b1 = d.get().toInt() and 0xFF
                        val b2 = d.get().toInt()
                        ((b2 shl 16) or (b1 shl 8) or b0) / 8388608.0
                    }
                    bits == 32 -> d.int / 2147483648.0
                    else -> throw IllegalArgumentException("Unsupported WAV format: ${file.name}")
                }
                interleaved[p++] = v.toFloat()
            }
        }
        return convertToStereo48k(interleaved, channels, sampleRate)
    }

    /**
     * Downmix (if needed) + resample interleaved float PCM to stereo
     * 48 kHz — the engine's canonical format. Linear resampling is
     * fine for stems.
     */
    fun convertToStereo48k(interleaved: FloatArray, channels: Int, sampleRate: Int): Pcm {
        require(channels in 1..8) { "Unsupported channel count: $channels" }
        val frameCount = interleaved.size / channels
        val mono = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var acc = 0.0
            for (c in 0 until channels) acc += interleaved[f * channels + c]
            mono[f] = (acc / channels).toFloat()
        }
        val outFrames = if (sampleRate == TARGET_RATE) frameCount
        else (frameCount.toDouble() * TARGET_RATE / sampleRate).roundToInt()
        val stereo = FloatArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val srcPos = i.toDouble() * sampleRate / TARGET_RATE
            val i0 = srcPos.toInt().coerceIn(0, frameCount - 1)
            val i1 = (i0 + 1).coerceIn(0, frameCount - 1)
            val frac = (srcPos - i0).toFloat()
            val s = mono[i0] * (1 - frac) + mono[i1] * frac
            stereo[i * 2] = s
            stereo[i * 2 + 1] = s
        }
        return Pcm(stereo, outFrames)
    }

    fun writeWav(file: File, stereo: FloatArray, sampleRate: Int) {
        val frames = stereo.size / 2
        val dataBytes = frames * 2 * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(2) // stereo
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 4) // byteRate
        buf.putShort(4) // blockAlign
        buf.putShort(16) // bits
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        for (s in stereo) {
            buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        file.writeBytes(buf.array())
    }
}
