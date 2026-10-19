package com.bandsync.session

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.bandsync.audio.AudioDecoder
import com.bandsync.audio.Song
import com.bandsync.audio.StemMeta
import com.bandsync.audio.WavIo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Song import: folder or file picker → each audio file is decoded once
 * (WAV read directly, everything else via MediaCodec) and stored as
 * 16-bit stereo 48 kHz WAV, so the playback engine never changes.
 *
 * Song id = the folder name on disk and never changes after import —
 * it is the cross-phone join key, so renaming a song later only edits
 * song.json's display name.
 */
object SongImporter {

    data class Progress(val label: String, val done: Int, val total: Int)

    private val AUDIO_EXTS = setOf("wav", "mp3", "m4a", "aac", "flac", "ogg", "opus")

    private fun isAudio(name: String?): Boolean {
        if (name == null) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTS
    }

    private fun sanitize(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "song" }

    private fun uniqueId(songsDir: File, base: String): String {
        var id = sanitize(base)
        if (!File(songsDir, id).exists()) return id
        repeat(50) {
            val cand = "${id}_${Random.nextInt(1000, 9999)}"
            if (!File(songsDir, cand).exists()) return cand
        }
        return "${id}_${System.currentTimeMillis()}"
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) {
                val n = c.getString(idx)
                if (!n.isNullOrBlank()) return n
            }
        }
        return "track"
    }

    /** Import every audio file inside a picked folder as one song. */
    fun importTree(
        ctx: Context,
        treeUri: Uri,
        songName: String,
        bpm: Double,
        beatsPerBar: Int,
        songsDir: File,
        onProgress: (Progress) -> Unit
    ): Song {
        val doc = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: throw IllegalArgumentException("Couldn't open that folder.")
        val files = doc.listFiles()
            .filter { it.isFile && isAudio(it.name) }
            .sortedBy { it.name.orEmpty() }
        require(files.isNotEmpty()) { "No audio files (wav/mp3/m4a/flac) in that folder." }
        val items = files.map { (it.uri to (it.name ?: "track")) }
        return importItems(ctx, items, songName, bpm, beatsPerBar, songsDir, onProgress)
    }

    /** Import explicitly picked audio files as one song. */
    fun importFiles(
        ctx: Context,
        uris: List<Uri>,
        songName: String,
        bpm: Double,
        beatsPerBar: Int,
        songsDir: File,
        onProgress: (Progress) -> Unit
    ): Song {
        require(uris.isNotEmpty()) { "No files picked." }
        val items = uris.map { uri -> uri to displayName(ctx, uri) }
            .filter { isAudio(it.second) }
        require(items.isNotEmpty()) { "None of the picked files are audio." }
        return importItems(ctx, items, songName, bpm, beatsPerBar, songsDir, onProgress)
    }

    private fun importItems(
        ctx: Context,
        items: List<Pair<Uri, String>>,
        songName: String,
        bpm: Double,
        beatsPerBar: Int,
        songsDir: File,
        onProgress: (Progress) -> Unit
    ): Song {
        require(bpm in 20.0..400.0) { "BPM must be 20–400." }
        val id = uniqueId(songsDir, songName)
        val dir = File(songsDir, id).apply { mkdirs() }
        try {
            val stems = mutableListOf<StemMeta>()
            items.forEachIndexed { i, (uri, name) ->
                onProgress(Progress("Decoding $name…", i, items.size))
                val tmp = File.createTempFile("bandsync_imp", ".bin", ctx.cacheDir)
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        tmp.outputStream().use { outs -> ins.copyTo(outs) }
                    } ?: throw IllegalArgumentException("Can't read $name")
                    val pcm = if (name.lowercase().endsWith(".wav")) {
                        WavIo.readWav(tmp)
                    } else {
                        AudioDecoder.decodeToPcm(tmp)
                    }
                    val stemFile = sanitize(name.substringBeforeLast('.')) + ".wav"
                    WavIo.writeWav(File(dir, stemFile), pcm.samples, WavIo.TARGET_RATE)
                    stems.add(StemMeta(name.substringBeforeLast('.'), stemFile))
                } finally {
                    tmp.delete()
                }
            }
            require(stems.isNotEmpty()) { "No stems imported." }
            onProgress(Progress("Writing song…", items.size, items.size))
            val json = JSONObject()
            json.put("id", id)
            json.put("name", songName.trim().ifEmpty { id })
            json.put("nativeBpm", bpm)
            json.put("beatsPerBar", beatsPerBar)
            val arr = JSONArray()
            stems.forEach { arr.put(JSONObject().put("name", it.name).put("file", it.file)) }
            json.put("stems", arr)
            File(dir, "song.json").writeText(json.toString(2))
            return Song.load(dir) ?: throw IllegalStateException("Import wrote an unreadable song.")
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }
}
