package com.bandsync.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny JSON-lines protocol over TCP. Every message is one line of JSON.
 *
 * Client -> host:  hi {name}
 * Host -> client:  welcome {hostName}
 * Client -> host:  ping {t1}          (t1 = client wall-clock ms)
 * Host -> client:  pong {t1, t2, t3}  (t2 = host recv ms, t3 = host send ms)
 * Host -> all:     transport {state, songId, songName, nativeBpm, targetBpm,
 *                             beatsPerBar, countInBars, loop, startHostMs}
 * Either -> other: bye {}
 */
object Protocol {
    const val VERSION = 1

    fun hi(name: String) = JSONObject().apply {
        put("type", "hi")
        put("v", VERSION)
        put("name", name)
    }

    fun welcome(hostName: String) = JSONObject().apply {
        put("type", "welcome")
        put("v", VERSION)
        put("hostName", hostName)
    }

    fun ping(t1: Long) = JSONObject().apply {
        put("type", "ping")
        put("t1", t1)
    }

    fun pong(t1: Long, t2: Long, t3: Long) = JSONObject().apply {
        put("type", "pong")
        put("t1", t1)
        put("t2", t2)
        put("t3", t3)
    }

    fun transportPlay(
        songId: String,
        songName: String,
        nativeBpm: Double,
        targetBpm: Double,
        beatsPerBar: Int,
        countInBars: Int,
        loop: Boolean,
        startHostMs: Long,
        startBeat: Double = 0.0,
        tempoMap: JSONArray? = null
    ) = JSONObject().apply {
        put("type", "transport")
        put("state", "play")
        put("songId", songId)
        put("songName", songName)
        put("nativeBpm", nativeBpm)
        put("targetBpm", targetBpm)
        put("beatsPerBar", beatsPerBar)
        put("countInBars", countInBars)
        put("loop", loop)
        put("startHostMs", startHostMs)
        // Song position (quarter-note beats) the timeline will be at when
        // startHostMs arrives. Phones seek their stems there instead of
        // starting from the top — used by the PC/DAW host.
        put("startBeat", startBeat)
        // DAW tempo track, [{b: beat, bpm: bpm}], for following tempo
        // changes. Absent/empty = fixed targetBpm (phone-hosted sessions).
        if (tempoMap != null) put("tempoMap", tempoMap)
    }

    fun transportStop() = JSONObject().apply {
        put("type", "transport")
        put("state", "stop")
    }

    fun bye() = JSONObject().apply { put("type", "bye") }
}

/** Parsed transport command. Null when the message is not a transport command. */
data class TransportCmd(
    val state: String,
    val songId: String = "",
    val songName: String = "",
    val nativeBpm: Double = 0.0,
    val targetBpm: Double = 0.0,
    val beatsPerBar: Int = 4,
    val countInBars: Int = 1,
    val loop: Boolean = true,
    val startHostMs: Long = 0L,
    val startBeat: Double = 0.0,
    val tempoMap: TempoMap? = null
) {
    companion object {
        fun parse(msg: JSONObject): TransportCmd? {
            if (msg.optString("type") != "transport") return null
            return TransportCmd(
                state = msg.optString("state"),
                songId = msg.optString("songId"),
                songName = msg.optString("songName"),
                nativeBpm = msg.optDouble("nativeBpm"),
                targetBpm = msg.optDouble("targetBpm"),
                beatsPerBar = msg.optInt("beatsPerBar", 4),
                countInBars = msg.optInt("countInBars", 1),
                loop = msg.optBoolean("loop", true),
                startHostMs = msg.optLong("startHostMs"),
                startBeat = msg.optDouble("startBeat", 0.0),
                tempoMap = TempoMap.parse(msg.optJSONArray("tempoMap"))
            )
        }
    }
}
