"""BandSync host-side TCP server. Mirrors net/HostServer.kt."""

import json
import socket
import threading
import time

import protocol


class _Peer:
    def __init__(self, server, sock):
        self._server = server
        self._sock = sock
        self.name = "..."
        self._lock = threading.Lock()
        self._running = True
        self._wfile = sock.makefile("w", encoding="utf-8")
        t = threading.Thread(target=self._read_loop, daemon=True,
                             name="bandsync-peer")
        t.start()

    def send(self, msg):
        line = json.dumps(msg, separators=(",", ":"))
        with self._lock:
            if not self._running:
                return
            try:
                self._wfile.write(line + "\n")
                self._wfile.flush()
            except OSError:
                self.close()

    def close(self):
        with self._lock:
            self._running = False
        try:
            self._sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self._sock.close()
        except OSError:
            pass

    def _read_loop(self):
        try:
            rfile = self._sock.makefile("r", encoding="utf-8")
            for line in rfile:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                self._server._on_message(self, msg)
        except OSError:
            pass
        finally:
            self._server._on_closed(self)


class HostServer:
    """Accepts member phones, answers clock pings, broadcasts transport."""

    def __init__(self, host_name, on_join=None, on_leave=None):
        self._host_name = host_name
        self._on_join = on_join
        self._on_leave = on_leave
        self._peers = {}  # id -> _Peer
        self._lock = threading.Lock()
        self._next_id = 1
        self._running = False
        self._sock = None
        self.port = -1
        # Optional: () -> msg | None. Called when a member says hi; the
        # returned message goes ONLY to that peer (late-join catch-up).
        self.late_join_provider = None

    def start(self):
        self._running = True
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", 0))
        s.listen(16)
        self._sock = s
        self.port = s.getsockname()[1]
        threading.Thread(target=self._accept_loop, daemon=True,
                         name="bandsync-accept").start()

    def _accept_loop(self):
        while self._running:
            try:
                sock, _ = self._sock.accept()
            except OSError:
                break
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            with self._lock:
                pid = self._next_id
                self._next_id += 1
                self._peers[pid] = _Peer(self, sock)

    def _on_message(self, peer, msg):
        mtype = msg.get("type")
        if mtype == "hi":
            peer.name = str(msg.get("name", "Member"))[:24]
            peer.send(protocol.welcome(self._host_name))
            if self._on_join:
                self._on_join(peer.name)
            # Late joiner: hand them the current position so they seek
            # straight onto the moving timeline.
            catch_up = None
            if self.late_join_provider:
                try:
                    catch_up = self.late_join_provider()
                except Exception:
                    catch_up = None
            if catch_up is not None:
                peer.send(catch_up)
        elif mtype == "ping":
            t1 = msg.get("t1", 0)
            t2 = int(time.time() * 1000)
            peer.send(protocol.pong(t1, t2, int(time.time() * 1000)))
        elif mtype == "bye":
            peer.close()

    def _on_closed(self, peer):
        name = None
        with self._lock:
            for pid, p in list(self._peers.items()):
                if p is peer:
                    del self._peers[pid]
                    name = p.name
                    break
        if name and name != "..." and self._on_leave:
            self._on_leave(name)

    def broadcast(self, msg):
        with self._lock:
            peers = list(self._peers.values())
        for p in peers:
            p.send(msg)

    def member_names(self):
        with self._lock:
            return [p.name for p in self._peers.values() if p.name != "..."]

    def stop(self):
        self._running = False
        try:
            self.broadcast(protocol.bye())
        except OSError:
            pass
        with self._lock:
            peers = list(self._peers.values())
            self._peers.clear()
        for p in peers:
            p.close()
        try:
            self._sock.close()
        except OSError:
            pass
