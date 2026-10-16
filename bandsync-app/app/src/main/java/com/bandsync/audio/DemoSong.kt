package com.bandsync.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesizes a demo song (8 bars, 100 BPM, 4/4) so two phones can be
 * tested with zero setup: kick, snare, hats, bass and pad stems.
 * Am – F – C – G, two bars each.
 */
object DemoSong {
    const val ID = "demo_groove_100"

    private fun midiToFreq(m: Int): Double = 440.0 * Math.pow(2.0, (m - 69) / 12.0)

    fun create(songsDir: File): Song {
        val dir = File(songsDir, ID)
        dir.mkdirs()
        val bpm = 100.0
        val beatsPerBar = 4
        val bars = 8
        val sr = 44100
        val totalBeats = beatsPerBar * bars
        val totalFrames = (totalBeats * 60.0 / bpm * sr).roundToInt()
        val rnd = Random(7)

        fun empty() = FloatArray(totalFrames * 2)

        fun add(buf: FloatArray, frame: Int, v: Float) {
            val i = (frame * 2).coerceIn(0, buf.size - 2)
            buf[i] += v
            buf[i + 1] += v
        }

        fun beatToFrame(b: Double): Int = (b * 60.0 / bpm * sr).roundToInt()

        val kick = empty()
        val snare = empty()
        val hats = empty()
        val bass = empty()
        val pad = empty()

        // Kick: four on the floor, pitch-dropping sine
        for (b in 0 until totalBeats) {
            val f0 = beatToFrame(b.toDouble())
            val len = (sr * 0.28).toInt()
            var phase = 0.0
            for (n in 0 until len) {
                val t = n / sr.toDouble()
                val freq = 45.0 + 80.0 * exp(-t * 28.0)
                phase += 2 * PI * freq / sr
                add(kick, f0 + n, (sin(phase) * exp(-t * 13.0) * 0.95).toFloat())
            }
        }

        // Snare: beats 2 and 4 — noise + 190 Hz body
        for (b in 0 until totalBeats) {
            if (b % 4 != 1 && b % 4 != 3) continue
            val f0 = beatToFrame(b.toDouble())
            val len = (sr * 0.22).toInt()
            for (n in 0 until len) {
                val t = n / sr.toDouble()
                val noise = (rnd.nextFloat() * 2f - 1f) * exp(-t * 26.0).toFloat() * 0.5f
                val body = (sin(2 * PI * 190 * t) * exp(-t * 22.0) * 0.45).toFloat()
                add(snare, f0 + n, noise + body)
            }
        }

        // Hats: 8th notes, accent on the beat
        for (h in 0 until totalBeats * 2) {
            val f0 = beatToFrame(h / 2.0)
            val len = (sr * 0.06).toInt()
            val amp = if (h % 2 == 0) 0.30f else 0.18f
            var prev = 0f
            for (n in 0 until len) {
                val t = n / sr.toDouble()
                val nz = rnd.nextFloat() * 2f - 1f
                val hp = nz - prev // crude highpass
                prev = nz
                add(hats, f0 + n, (hp * exp(-t * 95.0) * amp).toFloat())
            }
        }

        // Bass: 8th-note roots, Am F C G two bars each
        val roots = intArrayOf(33, 33, 29, 29, 36, 36, 31, 31) // A1 F1 C2 G1 per bar
        for (bar in 0 until bars) {
            val f = midiToFreq(roots[bar])
            for (e in 0 until 8) {
                val f0 = beatToFrame(bar * 4 + e / 2.0)
                val len = (sr * 0.42).toInt()
                for (n in 0 until len) {
                    val t = n / sr.toDouble()
                    val env = (t / 0.012).coerceAtMost(1.0) * exp(-t * 5.5)
                    val v = sin(2 * PI * f * t) + 0.4 * sin(4 * PI * f * t) + 0.18 * sin(6 * PI * f * t)
                    add(bass, f0 + n, (v * env * 0.34).toFloat())
                }
            }
        }

        // Pad: whole-bar chord stabs, soft attack
        val chords = arrayOf(
            intArrayOf(57, 60, 64), // Am
            intArrayOf(53, 57, 60), // F
            intArrayOf(55, 60, 64), // C (G-C-E voicing)
            intArrayOf(55, 59, 62)  // G
        )
        for (bar in 0 until bars) {
            val chord = chords[bar / 2]
            val f0 = beatToFrame(bar * 4.0)
            val len = (sr * 60.0 / bpm * 4).toInt()
            for (n in 0 until len) {
                val t = n / sr.toDouble()
                val attack = (t / 0.35).coerceAtMost(1.0)
                val rel = ((len - n) / sr.toDouble() / 0.4).coerceAtMost(1.0)
                var v = 0.0
                for (m in chord) v += sin(2 * PI * midiToFreq(m) * t)
                add(pad, f0 + n, (v / chord.size * attack * rel * 0.30).toFloat())
            }
        }

        val stems = listOf(
            "Kick" to kick, "Snare" to snare, "Hats" to hats, "Bass" to bass, "Pad" to pad
        )
        val files = mutableListOf<String>()
        for ((name, buf) in stems) {
            // normalize to 0.85 peak
            var peak = 0f
            for (s in buf) peak = maxOf(peak, kotlin.math.abs(s))
            if (peak > 0f) {
                val g = 0.85f / peak
                for (i in buf.indices) buf[i] *= g
            }
            val fname = name.lowercase() + ".wav"
            WavIo.writeWav(File(dir, fname), buf, sr)
            files.add("""{"name": "$name", "file": "$fname"}""")
        }
        val json = """{"name": "Demo Groove 100 BPM", "nativeBpm": 100.0, "beatsPerBar": 4,
            |"stems": [${files.joinToString(", ")}]}""".trimMargin()
        File(dir, "song.json").writeText(json)
        return Song.load(dir) ?: throw IllegalStateException("Demo song failed to load")
    }
}
