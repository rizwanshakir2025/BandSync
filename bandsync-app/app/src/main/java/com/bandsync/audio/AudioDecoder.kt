package com.bandsync.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any Android-supported compressed audio (mp3, m4a/aac, flac,
 * ogg…) to the engine's canonical format: interleaved stereo float at
 * 48 kHz. Used once at import time — playback always reads WAV.
 */
object AudioDecoder {

    fun decodeToPcm(src: File): WavIo.Pcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(src.absolutePath)
            var track = -1
            var mime: String? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    track = i
                    mime = m
                    break
                }
            }
            require(track >= 0) { "No audio track: ${src.name}" }
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(mime!!)
            try {
                codec.configure(extractor.getTrackFormat(track), null, null, 0)
                codec.start()
                val sink = ByteArrayOutputStream()
                var outRate = 44100
                var outChannels = 2
                var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
                val info = MediaCodec.BufferInfo()
                var inputEos = false
                var outputEos = false
                val timeoutUs = 10_000L
                while (!outputEos) {
                    if (!inputEos) {
                        val inIdx = codec.dequeueInputBuffer(timeoutUs)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf == null) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                            } else {
                                val n = extractor.readSampleData(inBuf, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(
                                        inIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEos = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                    when {
                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val outBuf = codec.getOutputBuffer(outIdx)
                                if (outBuf != null) {
                                    val chunk = ByteArray(info.size)
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    outBuf.get(chunk)
                                    sink.write(chunk)
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                    }
                }
                val raw = sink.toByteArray()
                require(raw.isNotEmpty()) { "Decoder produced no audio: ${src.name}" }
                val bytesPerSample = when (pcmEncoding) {
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                    else -> throw IllegalArgumentException(
                        "Unsupported decoder output encoding: $pcmEncoding"
                    )
                }
                val frames = raw.size / (bytesPerSample * outChannels)
                val interleaved = FloatArray(frames * outChannels)
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                var p = 0
                repeat(frames) {
                    repeat(outChannels) {
                        interleaved[p++] = if (bytesPerSample == 4) {
                            bb.float
                        } else {
                            bb.short / 32768f
                        }
                    }
                }
                return WavIo.convertToStereo48k(interleaved, outChannels, outRate)
            } finally {
                try {
                    codec.stop()
                } catch (_: Exception) {
                }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }
}
