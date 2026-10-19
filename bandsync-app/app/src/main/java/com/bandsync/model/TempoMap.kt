package com.bandsync.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * Song tempo map: the DAW's tempo track as (beat → BPM) points in
 * quarter-note beats. Broadcast once by the PC host; every phone then
 * precomputes the identical beat↔time function, so tempo changes need
 * no mid-song messages and all phones stay locked through them.
 *
 * A fixed tempo is just a single-point map. Tempo maps only ever come
 * from the PC/DAW host (which always sends loop=false), so looping
 * with a map is not a case the engines handle.
 */
data class TempoPoint(val beat: Double, val bpm: Double)

class TempoMap(val points: List<TempoPoint>) {
    init {
        require(points.isNotEmpty()) { "TempoMap needs at least one point" }
    }

    companion object {
        fun fixed(bpm: Double) = TempoMap(listOf(TempoPoint(0.0, bpm)))

        fun parse(arr: JSONArray?): TempoMap? {
            if (arr == null || arr.length() == 0) return null
            val pts = mutableListOf<TempoPoint>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                pts.add(TempoPoint(o.getDouble("b"), o.getDouble("bpm")))
            }
            if (pts.isEmpty()) return null
            return TempoMap(pts.sortedBy { it.beat })
        }
    }

    fun toJson(): JSONArray {
        val arr = JSONArray()
        points.forEach { arr.put(JSONObject().put("b", it.beat).put("bpm", it.bpm)) }
        return arr
    }

    /** BPM in effect at song beat [beat]. */
    fun bpmAt(beat: Double): Double {
        var bpm = points[0].bpm
        for (p in points) {
            if (p.beat <= beat) bpm = p.bpm else break
        }
        return bpm
    }

    private fun segIndex(beat: Double): Int {
        var i = 0
        for (idx in points.indices) {
            if (points[idx].beat <= beat) i = idx else break
        }
        return i
    }

    /**
     * Wall-clock milliseconds from [anchorBeat] to [targetBeat]
     * (targetBeat >= anchorBeat).
     */
    fun msBetween(anchorBeat: Double, targetBeat: Double): Double {
        if (targetBeat <= anchorBeat) return 0.0
        var ms = 0.0
        var b = anchorBeat
        var i = segIndex(anchorBeat)
        while (b < targetBeat) {
            val segEnd = if (i + 1 < points.size) points[i + 1].beat else targetBeat
            val end = min(segEnd, targetBeat)
            ms += (end - b) * 60000.0 / points[i].bpm
            b = end
            i++
        }
        return ms
    }

    /**
     * Output frames (48 kHz) consumed from song start to song beat [beat],
     * for stems rendered at [nativeBpm]. Accounts for per-segment stretch.
     */
    fun framesForBeat(beat: Double, nativeBpm: Double): Long {
        if (beat <= 0) return 0L
        val fpbNative = 48000.0 * 60.0 / nativeBpm
        var frames = 0.0
        var b = 0.0
        var i = 0
        while (b < beat) {
            val segEnd = if (i + 1 < points.size) points[i + 1].beat else beat
            val end = min(segEnd, beat)
            val ratio = points[i].bpm / nativeBpm
            frames += (end - b) * fpbNative * ratio
            b = end
            i++
        }
        return frames.toLong()
    }

    /**
     * (frame, speedRatio) switch points for tempo boundaries after
     * [fromBeat]. The engine flips playback speed exactly on these frames.
     */
    fun switchesAfter(fromBeat: Double, nativeBpm: Double): List<Pair<Long, Float>> =
        points.filter { it.beat > fromBeat }
            .map { p -> framesForBeat(p.beat, nativeBpm) to (p.bpm / nativeBpm).toFloat() }
}
