package com.bandsync.net

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * One JSON-lines TCP connection. Reader runs on its own daemon thread;
 * [onMessage] is invoked on that thread — hop to wherever you need.
 */
class PeerConnection(
    private val socket: Socket,
    private val onMessage: (JSONObject) -> Unit,
    private val onClosed: () -> Unit
) {
    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    private val writeLock = Any()

    @Volatile
    private var running = true

    init {
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        onMessage(JSONObject(line))
                    } catch (_: Exception) {
                        // malformed line — ignore, keep the connection alive
                    }
                }
            } catch (_: Exception) {
                // socket closed or reset
            } finally {
                running = false
                try { onClosed() } catch (_: Exception) { }
            }
        }, "bandsync-peer").apply { isDaemon = true; start() }
    }

    fun send(msg: JSONObject) {
        synchronized(writeLock) {
            if (!running) return
            try {
                writer.write(msg.toString())
                writer.write("\n")
                writer.flush()
            } catch (_: Exception) {
                close()
            }
        }
    }

    fun close() {
        running = false
        try { socket.close() } catch (_: Exception) { }
    }
}
