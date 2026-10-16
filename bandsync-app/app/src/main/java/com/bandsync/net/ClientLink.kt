package com.bandsync.net

import com.bandsync.model.Protocol
import com.bandsync.model.TransportCmd
import com.bandsync.sync.ClockSync
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Member side: connects to the host, keeps the clock sync alive with
 * pings, and delivers transport commands. Callbacks run on network threads.
 */
class ClientLink(
    private val memberName: String,
    private val clock: ClockSync,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected(hostName: String)
        fun onTransport(cmd: TransportCmd)
        fun onDisconnected()
        fun onError(message: String)
    }

    @Volatile
    private var running = false
    private var conn: PeerConnection? = null

    fun connect(host: String, port: Int) {
        running = true
        Thread({
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(host, port), 5000)
                sock.soTimeout = 0
                conn = PeerConnection(sock, ::onMessage, ::onClosed)
                conn?.send(Protocol.hi(memberName))
                startPingLoop()
            } catch (e: Exception) {
                running = false
                listener.onError("Could not reach host: ${e.message}")
            }
        }, "bandsync-connect").apply { isDaemon = true; start() }
    }

    private fun onMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "welcome" -> listener.onConnected(msg.optString("hostName", "Host"))
            "pong" -> {
                val t4 = System.currentTimeMillis()
                clock.addSample(msg.optLong("t1"), msg.optLong("t2"), msg.optLong("t3"), t4)
            }
            "transport" -> TransportCmd.parse(msg)?.let { listener.onTransport(it) }
            "bye" -> disconnect()
        }
    }

    private fun onClosed() {
        if (!running) return
        running = false
        listener.onDisconnected()
    }

    private fun startPingLoop() {
        Thread({
            // a few fast pings to converge, then steady 2s cadence
            try { Thread.sleep(400) } catch (_: InterruptedException) { return@Thread }
            repeat(4) {
                if (!running) return@Thread
                conn?.send(Protocol.ping(System.currentTimeMillis()))
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
            }
            while (running) {
                conn?.send(Protocol.ping(System.currentTimeMillis()))
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "bandsync-ping").apply { isDaemon = true; start() }
    }

    fun disconnect() {
        running = false
        try { conn?.send(Protocol.bye()) } catch (_: Exception) { }
        conn?.close()
        conn = null
    }
}
