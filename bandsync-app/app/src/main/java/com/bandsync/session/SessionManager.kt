package com.bandsync.session

import android.content.Context
import com.bandsync.audio.ClickEngine
import com.bandsync.audio.DemoSong
import com.bandsync.audio.Song
import com.bandsync.audio.StemEngine
import com.bandsync.audio.WavIo
import com.bandsync.model.Protocol
import com.bandsync.model.TransportCmd
import com.bandsync.net.ClientLink
import com.bandsync.net.Discovery
import com.bandsync.net.HostServer
import com.bandsync.service.SyncService
import com.bandsync.sync.ClockSync
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.ceil

/**
 * Owns the whole session: host server or member link, clock sync,
 * stem + click engines. The foreground [SyncService] keeps the process
 * (and the Wi-Fi radio) alive while the screen is locked; this object
 * does the actual work so Activities can come and go.
 *
 * All callbacks arrive on network threads — StateFlow is thread-safe.
 */
object SessionManager {

    data class UiState(
        val status: String = "idle", // idle | starting | live | error
        val role: String = "",       // host | member
        val selfName: String = "",
        val hostName: String = "",
        val songName: String = "",
        val songId: String = "",
        val targetBpm: Double = 0.0,
        val isPlaying: Boolean = false,
        val members: List<String> = emptyList(),
        val syncText: String = "",
        val note: String = ""
    )

    val uiState = MutableStateFlow(UiState())
    val songs = MutableStateFlow<List<Song>>(emptyList())
    val stemNames = MutableStateFlow<List<String>>(emptyList())
    val stemVolumes = MutableStateFlow<Map<String, Float>>(emptyMap())
    val clickEnabled = MutableStateFlow(true)
    val clickVolume = MutableStateFlow(0.8f)
    val busy = MutableStateFlow(false)

    private var appContext: Context? = null
    private var server: HostServer? = null
    private var discovery: Discovery? = null
    private var client: ClientLink? = null
    private val clock = ClockSync()
    private val stems = StemEngine()
    private val click = ClickEngine()

    private var currentSong: Song? = null
    private var targetBpm = 120.0
    private var nativeBpm = 120.0
    private var beatsPerBar = 4
    private var countInBars = 1
    private var loop = true

    private fun songsDir(): File {
        val ctx = appContext ?: throw IllegalStateException("No context")
        return File(ctx.filesDir, "songs").apply { mkdirs() }
    }

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
        refreshSongs()
    }

    fun refreshSongs() {
        try {
            songs.value = Song.loadAll(songsDir())
        } catch (_: Exception) { }
    }

    fun createDemoSong() {
        if (busy.value) return
        busy.value = true
        Thread({
            try {
                DemoSong.create(songsDir())
                refreshSongs()
                setNote("Demo song created — pick it when hosting.")
            } catch (e: Exception) {
                setNote("Demo song failed: ${e.message}")
            } finally {
                busy.value = false
            }
        }, "bandsync-demo").apply { isDaemon = true; start() }
    }

    fun clearNote() {
        uiState.value = uiState.value.copy(note = "")
    }

    fun postNote(t: String) = setNote(t)

    private fun setNote(t: String) {
        uiState.value = uiState.value.copy(note = t)
    }

    // ---------------- host ----------------

    fun startHosting(ctx: Context, song: Song, bpm: Double, countIn: Int, loopMode: Boolean, hostName: String) {
        shutdown()
        appContext = ctx.applicationContext
        uiState.value = UiState(status = "starting", role = "host", selfName = hostName)
        SyncService.start(ctx, SyncService.ACTION_HOST)
        Thread({
            try {
                currentSong = song
                targetBpm = bpm
                nativeBpm = song.nativeBpm
                beatsPerBar = song.beatsPerBar
                countInBars = countIn
                loop = loopMode
                stems.load(song)
                stemNames.value = stems.stemNames
                stemVolumes.value = stems.stemNames.associateWith { 1.0f }
                val srv = HostServer(hostName, object : HostServer.Listener {
                    override fun onMemberJoined(name: String) = updateMembers()
                    override fun onMemberLeft(name: String) = updateMembers()
                })
                server = srv
                srv.start()
                var tries = 0
                while (srv.port <= 0 && tries++ < 100) Thread.sleep(50)
                if (srv.port <= 0) {
                    uiState.value = uiState.value.copy(status = "error", note = "Could not open server socket.")
                    return@Thread
                }
                discovery = Discovery(appContext!!).also { it.register(hostName, srv.port) }
                uiState.value = UiState(
                    status = "live", role = "host", selfName = hostName,
                    hostName = hostName, songName = song.name, songId = song.id,
                    targetBpm = bpm, members = srv.memberNames(),
                    syncText = "Hosting on port ${srv.port}"
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(status = "error", note = "Host start failed: ${e.message}")
            }
        }, "bandsync-host-start").apply { isDaemon = true; start() }
    }

    private fun updateMembers() {
        val names = server?.memberNames() ?: emptyList()
        uiState.value = uiState.value.copy(members = names)
    }

    /** Host transport: schedule the downbeat 1.5 s in the future for everyone. */
    fun hostPlay() {
        val song = currentSong ?: return
        if (uiState.value.role != "host") return
        val startHostMs = System.currentTimeMillis() + 1500
        server?.broadcast(
            Protocol.transportPlay(
                song.id, song.name, nativeBpm, targetBpm,
                beatsPerBar, countInBars, loop, startHostMs
            )
        )
        startEngines(startHostMs, countInBars, loop)
        uiState.value = uiState.value.copy(isPlaying = true)
    }

    fun hostStop() {
        if (uiState.value.role != "host") return
        server?.broadcast(Protocol.transportStop())
        stopEngines()
        uiState.value = uiState.value.copy(isPlaying = false)
    }

    // ---------------- member ----------------

    fun startJoin(ctx: Context, hostAddr: String, port: Int, name: String) {
        shutdown()
        appContext = ctx.applicationContext
        uiState.value = UiState(status = "starting", role = "member", selfName = name)
        SyncService.start(ctx, SyncService.ACTION_JOIN)
        val link = ClientLink(name, clock, object : ClientLink.Listener {
            override fun onConnected(hostName: String) {
                uiState.value = uiState.value.copy(status = "live", hostName = hostName)
            }

            override fun onTransport(cmd: TransportCmd) = onClientTransport(cmd)

            override fun onDisconnected() {
                stopEngines()
                if (uiState.value.status == "live" || uiState.value.status == "starting") {
                    uiState.value = uiState.value.copy(status = "idle", note = "Disconnected from host.")
                }
            }

            override fun onError(message: String) {
                uiState.value = UiState(status = "error", role = "member", selfName = name, note = message)
            }
        })
        client = link
        link.connect(hostAddr, port)
        Thread({
            while (client === link) {
                val s = "offset %+.1f ms · jitter %.1f ms · rtt %d ms · %d samples".format(
                    clock.offsetMs(), clock.jitterMs(), clock.lastRttMs(), clock.sampleCount()
                )
                val cur = uiState.value
                if (cur.role == "member" && cur.syncText != s) {
                    uiState.value = cur.copy(syncText = s)
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }, "bandsync-syncui").apply { isDaemon = true; start() }
    }

    private fun onClientTransport(cmd: TransportCmd) {
        if (cmd.state == "stop") {
            stopEngines()
            uiState.value = uiState.value.copy(isPlaying = false)
            return
        }
        val song = try { Song.load(File(songsDir(), cmd.songId)) } catch (_: Exception) { null }
        if (song == null) {
            setNote("Song '${cmd.songName}' isn't on this phone — ask the host to share the stems first.")
            return
        }
        try {
            stems.load(song)
        } catch (e: Exception) {
            setNote("Couldn't load stems: ${e.message}")
            return
        }
        currentSong = song
        nativeBpm = cmd.nativeBpm
        targetBpm = cmd.targetBpm
        beatsPerBar = cmd.beatsPerBar
        stemNames.value = stems.stemNames
        val vols = stemVolumes.value.toMutableMap()
        stems.stemNames.forEach { vols.putIfAbsent(it, 1.0f) }
        stemVolumes.value = vols
        val startLocal = clock.hostToLocal(cmd.startHostMs)
        startEngines(startLocal, cmd.countInBars, cmd.loop, cmd.startBeat)
        uiState.value = uiState.value.copy(
            isPlaying = true, songName = cmd.songName, songId = cmd.songId, targetBpm = cmd.targetBpm
        )
    }

    // ---------------- engines ----------------

    private fun startEngines(
        startWallMs: Long,
        countIn: Int,
        loopMode: Boolean,
        startBeat: Double = 0.0
    ) {
        stopEngines()
        val beatMs = 60000.0 / targetBpm
        val ratio = (targetBpm / nativeBpm).toFloat()
        // Seek into the timeline (PC/DAW host): stems start at the frame the
        // song will be at, clicks resume on the next beat boundary with the
        // accent pattern glued to the song's downbeats.
        val startFrame = (startBeat * WavIo.TARGET_RATE * 60.0 / nativeBpm).toInt()
        val firstBeatIdx = ceil(startBeat).toInt()
        val firstClickMs = startWallMs -
            (countIn * beatsPerBar * beatMs).toLong() +
            ((firstBeatIdx - startBeat) * beatMs).toLong()
        val songBeats = stems.songBeats(nativeBpm)
        val totalClicks = if (loopMode) -1
        else (countIn * beatsPerBar + songBeats - startBeat).toInt().coerceAtLeast(1)
        stemVolumes.value.forEach { (n, v) -> stems.setVolume(n, v) }
        click.enabled = clickEnabled.value
        click.volume = clickVolume.value
        stems.play(startWallMs, ratio, loopMode, startFrame)
        click.start(firstClickMs, targetBpm, beatsPerBar, totalClicks, firstBeatIdx)
    }

    private fun stopEngines() {
        try { stems.stop() } catch (_: Exception) { }
        try { click.stop() } catch (_: Exception) { }
    }

    // ---------------- mixer (local only) ----------------

    fun setStemVolume(name: String, v: Float) {
        stemVolumes.value = stemVolumes.value.toMutableMap().also { it[name] = v }
        stems.setVolume(name, v)
    }

    fun setClickEnabled(b: Boolean) {
        clickEnabled.value = b
        click.enabled = b
    }

    fun setClickVolume(v: Float) {
        clickVolume.value = v
        click.volume = v
    }

    // ---------------- teardown ----------------

    fun shutdown() {
        stopEngines()
        try { client?.disconnect() } catch (_: Exception) { }
        client = null
        try { server?.stop() } catch (_: Exception) { }
        server = null
        try { discovery?.unregister() } catch (_: Exception) { }
        discovery = null
        currentSong = null
        uiState.value = UiState()
        stemNames.value = emptyList()
    }

    fun leave(ctx: Context) {
        shutdown()
        SyncService.stop(ctx)
    }
}
