package com.bandsync.audio

import org.json.JSONObject
import java.io.File

data class StemMeta(val name: String, val file: String)

/**
 * A song = a folder under <files>/songs/<id>/ containing song.json
 * plus one WAV file per stem. Stems are pre-shared: every phone holds
 * the same files, so the network only carries tiny control messages.
 *
 * song.json: {"name": "...", "nativeBpm": 100.0, "beatsPerBar": 4,
 *             "stems": [{"name": "Kick", "file": "kick.wav"}, ...]}
 */
data class Song(
    val id: String,
    val name: String,
    val nativeBpm: Double,
    val beatsPerBar: Int,
    val dir: File,
    val stems: List<StemMeta>
) {
    companion object {
        fun loadAll(songsDir: File): List<Song> {
            if (!songsDir.isDirectory) return emptyList()
            return songsDir.listFiles { f -> f.isDirectory }
                ?.mapNotNull { load(it) }
                ?.sortedBy { it.name }
                ?: emptyList()
        }

        fun load(dir: File): Song? {            return try {
                val jsonFile = File(dir, "song.json")
                if (!jsonFile.exists()) return null
                val json = JSONObject(jsonFile.readText())
                val arr = json.getJSONArray("stems")
                val stems = mutableListOf<StemMeta>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val f = o.getString("file")
                    if (File(dir, f).exists()) stems.add(StemMeta(o.getString("name"), f))
                }
                if (stems.isEmpty()) return null
                Song(
                    id = dir.name,
                    name = json.getString("name"),
                    nativeBpm = json.getDouble("nativeBpm"),
                    beatsPerBar = json.optInt("beatsPerBar", 4),
                    dir = dir,
                    stems = stems
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Rewrite song.json, preserving the stem list. The song id (folder
         * name) never changes, so cross-phone identity is stable.
         */
        fun saveMeta(dir: File, name: String, bpm: Double, beatsPerBar: Int) {
            val old = load(dir)
            val json = JSONObject()
            json.put("id", dir.name)
            json.put("name", name)
            json.put("nativeBpm", bpm)
            json.put("beatsPerBar", beatsPerBar)
            val arr = org.json.JSONArray()
            old?.stems?.forEach {
                arr.put(JSONObject().put("name", it.name).put("file", it.file))
            }
            json.put("stems", arr)
            File(dir, "song.json").writeText(json.toString(2))
        }
    }
}
