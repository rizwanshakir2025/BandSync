package com.bandsync.net

import com.bandsync.model.Protocol
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

/**
 * Host side: accepts member phones, answers clock pings, broadcasts transport.
 * Callbacks run on network threads.
 */
class HostServer(
    private val hostName: String,
    private val listener: Listener
) {
    interface Listener {
        fun onMemberJoined(name: String)
        fun onMemberLeft(name: String)
    }

    private data class Member(val conn: PeerConnection, var name: String)

    private val members = mutableMapOf<Int, Member>()
    private val lock = Any()
    private var nextId = 1

    /**
     * Built by the session owner: a catch-up transport for a member that
     * joins mid-song. Invoked on the network thread; may return null.
     */
    var lateJoinProvider: (() -> JSONObject?)? = null

    @Volatile
    private var running = false
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: -1

    fun start() {
        running = true
        val s = ServerSocket(0)
        server = s
        Thread({
            while (running) {
                try {
                    val sock: Socket = s.accept()
                    attach(sock)
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "bandsync-accept").apply { isDaemon = true; start() }
    }

    private fun attach(sock: Socket) {
        val id: Int
        synchronized(lock) {
            id = nextId++
            // placeholder until "hi" arrives
            val conn = PeerConnection(sock, { msg -> onMessage(id, msg) }, { onClosed(id) })
            members[id] = Member(conn, "…")
        }
    }

    private fun onMessage(id: Int, msg: JSONObject) {
        when (msg.optString("type")) {
            "hi" -> {
                val name = msg.optString("name", "Member").take(24)
                synchronized(lock) { members[id]?.name = name }
                synchronized(lock) { members[id]?.conn?.send(Protocol.welcome(hostName)) }
                listener.onMemberJoined(name)
                // Late joiner: hand them the current position so they seek
                // straight onto the moving timeline.
                val catchUp = try { lateJoinProvider?.invoke() } catch (_: Exception) { null }
                if (catchUp != null) {
                    synchronized(lock) { members[id]?.conn?.send(catchUp) }
                }
            }
            "ping" -> {
                val t1 = msg.optLong("t1")
                val t2 = System.currentTimeMillis()
                val reply = Protocol.pong(t1, t2, System.currentTimeMillis())
                synchronized(lock) { members[id]?.conn?.send(reply) }
            }
            "bye" -> {
                synchronized(lock) { members[id]?.conn?.close() }
            }
        }
    }

    private fun onClosed(id: Int) {
        val name = synchronized(lock) { members.remove(id)?.name ?: return }
        if (name != "…") listener.onMemberLeft(name)
    }

    fun broadcast(msg: JSONObject) {
        val conns = synchronized(lock) { members.values.map { it.conn } }
        conns.forEach { it.send(msg) }
    }

    fun memberNames(): List<String> =
        synchronized(lock) { members.values.map { it.name }.filter { it != "…" } }

    fun stop() {
        running = false
        try { broadcast(Protocol.bye()) } catch (_: Exception) { }
        val conns = synchronized(lock) { members.values.map { it.conn }.also { members.clear() } }
        conns.forEach { it.close() }
        try { server?.close() } catch (_: Exception) { }
        server = null
    }
}
