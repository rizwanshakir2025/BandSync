#!/usr/bin/env python3
"""BandSync Windows host.

Bridges a PC DAW's transport (via MIDI Clock on a virtual port such as
loopMIDI) to the BandSync phone protocol: on DAW play it broadcasts a
transport command scheduled HORIZON_MS in the future with the exact song
position (startBeat) the DAW will be at — so phones seek to the DAW's
timeline instead of starting late. On DAW stop it broadcasts stop.

The host never plays audio itself; the DAW plays through the PA while
each phone plays its own stems + click mix, locked to the DAW.

Usage:
  bandsync_host.py [--headless] [--simulate] [--name NAME]
                   [--bpm BPM] [--play-after S] [--stop-after S]

  --headless   no GUI (for servers / testing)
  --simulate   fake DAW clock instead of a MIDI port (no loopMIDI needed)
"""

import argparse
import os
import queue
import socket
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import protocol
from host_server import HostServer
from midi_clock import MidiClockBridge, SimulatedDaw
from songs import load_songs

HORIZON_MS = 1200  # phones schedule the downbeat this far ahead


def base_dir():
    """Folder the app lives in — next to the .exe when frozen with
    PyInstaller, next to this file when run from source."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def songs_dir():
    return os.path.join(base_dir(), "songs")


def load_tempo_map(path):
    """Parse a Standard MIDI File's tempo track → [(beat, bpm)].

    Export the DAW project's tempo track as .mid (bar 1 = beat 0, same as
    the project). Beats are quarter-note beats from the file start.
    """
    from mido import MidiFile
    mid = MidiFile(path)
    ppq = mid.ticks_per_beat
    events = []
    for track in mid.tracks:
        abs_tick = 0
        for msg in track:
            abs_tick += msg.time
            if msg.type == "set_tempo":
                events.append((abs_tick / ppq, 60_000_000 / msg.tempo))
    events.sort(key=lambda e: e[0])
    dedup = []
    for b, bpm in events:
        if dedup and abs(dedup[-1][0] - b) < 1e-9:
            dedup[-1] = (b, bpm)
        else:
            dedup.append((b, bpm))
    if not dedup:
        raise ValueError(f"No tempo events in {path}")
    if dedup[0][0] > 0:
        dedup.insert(0, (0.0, dedup[0][1]))
    return dedup


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class BandSyncHost:
    def __init__(self, host_name, log_fn):
        self._log = log_fn
        self.server = HostServer(
            host_name,
            on_join=lambda n: self._log(f"📱 {n} joined"),
            on_leave=lambda n: self._log(f"📱 {n} left"),
        )
        self.bridge = MidiClockBridge(
            on_start=self._daw_start,
            on_stop=self._daw_stop,
            on_drift=self._daw_drift,
        )
        self.song = None
        self.tempo = 120.0
        self.tempo_map = None      # [(beat, bpm)] from a MIDI tempo track
        self.tempo_map_path = ""
        self._playing = False
        self._play_bpm = 120.0
        self._events = queue.Queue()
        self._zeroconf = None
        self._drift_thread = None
        self.server.late_join_provider = self._catch_up
        self.bridge.expected_bpm_fn = self._expected_bpm

    # -- lifecycle ------------------------------------------------------

    def start(self):
        self.server.start()
        self._log(f"Listening on port {self.server.port}")
        self._register_mdns()
        ip = local_ip()
        self._log(f"Manual connect: {ip}:{self.server.port}")
        self._drift_thread = threading.Thread(target=self._drift_loop,
                                              daemon=True, name="drift")
        self._drift_thread.start()

    def _register_mdns(self):
        try:
            from zeroconf import ServiceInfo, Zeroconf
        except ImportError:
            self._log("zeroconf not installed — phones can still connect manually")
            return
        try:
            info = ServiceInfo(
                "_bandsync._tcp.local.",
                f"BandSync-PC._bandsync._tcp.local.",
                addresses=[socket.inet_aton(local_ip())],
                port=self.server.port,
                properties={"v": "1"},
            )
            zc = Zeroconf()
            zc.register_service(info)
            self._zeroconf = (zc, info)
            self._log("mDNS: BandSync-PC is discoverable")
        except Exception as e:  # noqa: BLE001 - best effort
            self._log(f"mDNS failed ({e}) — use manual connect")

    def stop(self):
        if self._zeroconf:
            try:
                self._zeroconf[0].unregister_service(self._zeroconf[1])
                self._zeroconf[0].close()
            except Exception:  # noqa: BLE001 - best effort
                pass
        self.server.stop()

    # -- tempo map ------------------------------------------------------

    def _map_bpm_at(self, beat):
        """BPM in effect at song beat `beat` per the loaded tempo map."""
        bpm = self.tempo_map[0][1]
        for b, x in self.tempo_map:
            if b <= beat:
                bpm = x
            else:
                break
        return bpm

    def _expected_bpm(self):
        """Tempo the DAW should be at right now (map-aware)."""
        beats = self.bridge.daw_beats()
        if self.tempo_map and beats is not None:
            return self._map_bpm_at(beats)
        return self._play_bpm

    def _tempo_map_json(self):
        if not self.tempo_map:
            return None
        return [{"b": b, "bpm": bpm} for b, bpm in self.tempo_map]

    # -- DAW callbacks (run on the MIDI thread) --------------------------

    def _daw_start(self, base_beats):
        if self.song is None:
            self._log("DAW started but no song selected — ignoring")
            return
        bpm = self._map_bpm_at(base_beats) if self.tempo_map else self.tempo
        self._play_bpm = bpm
        self._playing = True
        beat_ms = 60000.0 / bpm
        start_host_ms = time.time() * 1000.0 + HORIZON_MS
        start_beat = base_beats + HORIZON_MS / beat_ms
        self.server.broadcast(protocol.transport_play(
            song_id=self.song["id"],
            song_name=self.song["name"],
            native_bpm=self.song["nativeBpm"],
            target_bpm=bpm,
            beats_per_bar=self.song["beatsPerBar"],
            count_in_bars=0,      # DAW is already playing; phones seek in
            loop=False,           # DAW owns the timeline; it doesn't loop
            start_host_ms=start_host_ms,
            start_beat=start_beat,
            tempo_map=self._tempo_map_json(),
        ))
        self.bridge.broadcast_bpm = bpm
        self._events.put(("daw", True))
        map_note = f" + {len(self.tempo_map)} tempo events" if self.tempo_map else ""
        self._log(f"▶ DAW play @ {bpm:.1f} BPM — phones join at beat {start_beat:.2f}{map_note}")

    def _daw_stop(self):
        self._playing = False
        self.server.broadcast(protocol.transport_stop())
        self.bridge.broadcast_bpm = None
        self._events.put(("daw", False))
        self._log("■ DAW stop — phones stopped")

    def _daw_drift(self, measured):
        self._log(f"⚠ DAW tempo drift: ticks say {measured:.1f} BPM, "
                  f"expected {self._expected_bpm():.1f}. "
                  f"Restart the DAW transport to re-sync.")

    def _catch_up(self):
        """Transport for a phone joining mid-song: where the DAW is right
        now (from its own tick count) plus the horizon. None when idle."""
        if not self._playing or self.song is None:
            return None
        beats_now = self.bridge.daw_beats()
        if beats_now is None:
            return None
        bpm_now = self._map_bpm_at(beats_now) if self.tempo_map else self._play_bpm
        start_beat = beats_now + HORIZON_MS / (60000.0 / bpm_now)
        # one refinement: tempo in effect where the phones will actually start
        bpm = self._map_bpm_at(start_beat) if self.tempo_map else self._play_bpm
        start_host_ms = time.time() * 1000.0 + HORIZON_MS
        return protocol.transport_play(
            song_id=self.song["id"],
            song_name=self.song["name"],
            native_bpm=self.song["nativeBpm"],
            target_bpm=bpm,
            beats_per_bar=self.song["beatsPerBar"],
            count_in_bars=0,
            loop=False,
            start_host_ms=start_host_ms,
            start_beat=start_beat,
            tempo_map=self._tempo_map_json(),
        )

    def panic_stop(self):
        self.server.broadcast(protocol.transport_stop())
        self._log("■ Panic stop broadcast")

    def _drift_loop(self):
        while True:
            time.sleep(1.0)
            try:
                self.bridge.check_drift()
            except Exception:  # noqa: BLE001 - never kill the monitor
                pass


# --------------------------------------------------------------------------
# GUI
# --------------------------------------------------------------------------

def run_gui(args):
    import tkinter as tk
    from tkinter import ttk

    try:
        import mido
        midi_ports = mido.get_input_names()
    except ImportError:
        mido = None
        midi_ports = []

    host = BandSyncHost(args.name, log_fn=lambda s: None)
    songs = load_songs(songs_dir())

    root = tk.Tk()
    root.title("BandSync Host (PC)")
    root.geometry("560x560")

    log_lines = []

    def log(s):
        stamp = time.strftime("%H:%M:%S")
        log_lines.append(f"[{stamp}] {s}")
        del log_lines[:-200]
        log_text.config(state="normal")
        log_text.delete("1.0", "end")
        log_text.insert("end", "\n".join(log_lines))
        log_text.see("end")
        log_text.config(state="disabled")

    host._log = log
    host.start()

    # drain DAW on/off events into status labels
    def poll():
        try:
            while True:
                kind, playing = host._events.get_nowait()
                if kind == "daw":
                    daw_var.set("Playing" if playing else "Stopped")
        except queue.Empty:
            pass
        bpm = host.bridge.measured_bpm()
        meas_var.set(f"{bpm:.1f} BPM" if bpm else "—")
        members = host.server.member_names()
        mem_var.set(f"{len(members)}: {', '.join(members)}" if members else "none yet")
        root.after(300, poll)

    frm = ttk.Frame(root, padding=12)
    frm.pack(fill="both", expand=True)

    ttk.Label(frm, text="BandSync Host — PC", font=("TkDefaultFont", 14, "bold")).grid(
        row=0, column=0, columnspan=3, sticky="w", pady=(0, 8))

    ttk.Label(frm, text="MIDI clock in:").grid(row=1, column=0, sticky="w")
    port_var = tk.StringVar(value=midi_ports[0] if midi_ports else "")
    port_menu = ttk.OptionMenu(frm, port_var, port_var.get(), *midi_ports)
    port_menu.grid(row=1, column=1, sticky="ew")
    ttk.Button(frm, text="Refresh",
               command=lambda: refresh_ports()).grid(row=1, column=2)

    def refresh_ports():
        if mido is None:
            log("mido not installed — pip install -r requirements.txt")
            return
        ports = mido.get_input_names()
        menu = port_menu["menu"]
        menu.delete(0, "end")
        for p in ports:
            menu.add_command(label=p, command=lambda v=p: port_var.set(v))
        if ports:
            port_var.set(ports[0])
        log(f"MIDI ports: {ports if ports else 'none found'}")

    ttk.Label(frm, text="Song:").grid(row=2, column=0, sticky="w")
    song_var = tk.StringVar()
    song_names = [s["name"] for s in songs] or ["(no songs — add song.json files under songs/)"]
    song_menu = ttk.OptionMenu(frm, song_var, song_names[0], *song_names)
    song_menu.grid(row=2, column=1, columnspan=2, sticky="ew")

    ttk.Label(frm, text="Tempo (BPM):").grid(row=3, column=0, sticky="w")
    tempo_var = tk.StringVar(value="100")
    ttk.Entry(frm, textvariable=tempo_var, width=10).grid(row=3, column=1, sticky="w")
    ttk.Label(frm, text="Used when no tempo map is loaded.").grid(
        row=3, column=2, sticky="w")

    ttk.Label(frm, text="Tempo map:").grid(row=4, column=0, sticky="w")
    map_var = tk.StringVar(value="none — fixed tempo")
    ttk.Label(frm, textvariable=map_var, wraplength=260).grid(row=4, column=1, sticky="w")
    ttk.Button(frm, text="Load .mid…",
               command=lambda: load_map_dialog()).grid(row=4, column=2)

    def load_map_dialog(path=None):
        from tkinter import filedialog
        p = path or filedialog.askopenfilename(
            title="DAW tempo track",
            filetypes=[("MIDI files", "*.mid *.midi"), ("All files", "*.*")])
        if not p:
            return
        try:
            host.tempo_map = load_tempo_map(p)
            host.tempo_map_path = p
            first = host.tempo_map[0][1]
            map_var.set(f"{len(host.tempo_map)} events, starts {first:.1f} BPM")
            log(f"Tempo map loaded: {len(host.tempo_map)} events from "
                f"{os.path.basename(p)} — phones will follow the DAW's tempo track.")
        except Exception as e:  # noqa: BLE001 - report to user
            log(f"Tempo map failed: {e}")

    def apply_selection():
        name = song_var.get()
        match = next((s for s in songs if s["name"] == name), None)
        if match is None:
            log("No song selected")
            return
        host.song = match
        if host.tempo_map:
            host.tempo = host.tempo_map[0][1]
            log(f"Song: {match['name']} ({match['nativeBpm']:.0f} BPM native, "
                f"{len(match['stemNames'])} stems) → tempo follows the map "
                f"(starts {host.tempo:.1f} BPM)")
        else:
            try:
                host.tempo = float(tempo_var.get())
            except ValueError:
                log("Tempo must be a number")
                return
            log(f"Song: {match['name']} ({match['nativeBpm']:.0f} BPM native, "
                f"{len(match['stemNames'])} stems) → play at {host.tempo:.1f} BPM")

    ttk.Button(frm, text="Use this song + tempo",
               command=apply_selection).grid(row=5, column=0, columnspan=3,
                                            sticky="ew", pady=4)
    if songs:
        song_var.set(songs[0]["name"])
        apply_selection()

    daw_var = tk.StringVar(value="Stopped")
    meas_var = tk.StringVar(value="—")
    mem_var = tk.StringVar(value="none yet")
    ttk.Label(frm, text="DAW:").grid(row=6, column=0, sticky="w")
    ttk.Label(frm, textvariable=daw_var, font=("TkDefaultFont", 11, "bold")).grid(
        row=6, column=1, sticky="w")
    ttk.Label(frm, text="Measured:").grid(row=7, column=0, sticky="w")
    ttk.Label(frm, textvariable=meas_var).grid(row=7, column=1, sticky="w")
    ttk.Label(frm, text="Members:").grid(row=8, column=0, sticky="w")
    ttk.Label(frm, textvariable=mem_var, wraplength=380).grid(
        row=8, column=1, columnspan=2, sticky="w")

    btn_frm = ttk.Frame(frm)
    btn_frm.grid(row=9, column=0, columnspan=3, sticky="ew", pady=6)
    ttk.Button(btn_frm, text="Panic: stop all phones",
               command=host.panic_stop).pack(side="left", expand=True, fill="x")
    if args.simulate:
        sim = SimulatedDaw(host.bridge, bpm=float(args.bpm))
        ttk.Button(btn_frm, text="Simulate DAW play",
                   command=lambda: sim.play(beats=64)).pack(
                       side="left", expand=True, fill="x")
        ttk.Button(btn_frm, text="Simulate stop",
                   command=sim.stop).pack(side="left", expand=True, fill="x")
        if not songs:
            host.song = {"id": "demo_groove_100", "name": "Demo Groove 100 BPM",
                         "nativeBpm": 100.0, "beatsPerBar": 4, "stemNames": []}
        host.tempo = float(args.bpm)

    log_text = tk.Text(frm, height=12, state="disabled", wrap="word")
    log_text.grid(row=10, column=0, columnspan=3, sticky="nsew")
    frm.rowconfigure(10, weight=1)
    frm.columnconfigure(1, weight=1)

    # --tempo-map also works with the GUI
    if args.tempo_map:
        load_map_dialog(args.tempo_map)

    # MIDI input thread
    def midi_loop():
        if mido is None:
            log("mido not installed — run: pip install -r requirements.txt")
            return
        port_name = port_var.get()
        if not port_name:
            log("No MIDI port selected")
            return
        log(f"Opening MIDI in: {port_name}")
        try:
            with mido.open_input(port_name) as port:
                for msg in port:
                    host.bridge.handle_message(msg)
        except Exception as e:  # noqa: BLE001 - report and exit thread
            log(f"MIDI error: {e}")

    if not args.simulate:
        threading.Thread(target=midi_loop, daemon=True, name="midi").start()
    else:
        log("SIMULATE mode — no MIDI port needed")

    def on_close():
        host.stop()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_close)
    poll()
    log("Ready. Start the DAW transport (or Simulate) to drive the phones.")
    root.mainloop()


# --------------------------------------------------------------------------
# headless (servers / automated testing)
# --------------------------------------------------------------------------

def run_headless(args):
    events = []

    def log(s):
        print(f"[host] {s}", flush=True)

    host = BandSyncHost(args.name, log_fn=log)
    songs = load_songs(songs_dir())
    host.song = next((s for s in songs if s["id"] == "demo_groove_100"),
                     {"id": "demo_groove_100", "name": "Demo Groove 100 BPM",
                      "nativeBpm": 100.0, "beatsPerBar": 4, "stemNames": []})
    host.tempo = float(args.bpm)
    if args.tempo_map:
        host.tempo_map = load_tempo_map(args.tempo_map)
        host.tempo_map_path = args.tempo_map
        host.tempo = host.tempo_map[0][1]
        log(f"Tempo map: {len(host.tempo_map)} events from {args.tempo_map}")
    host.start()
    print(f"PORT={host.server.port}", flush=True)

    if args.simulate:
        sim = SimulatedDaw(host.bridge, bpm=float(args.bpm))
        time.sleep(float(args.play_after))
        log(f"simulating DAW play @ {args.bpm} BPM")
        sim.play(beats=64)
        time.sleep(float(args.stop_after))
        log("simulating DAW stop")
        sim.stop()
        time.sleep(1.0)
    else:
        try:
            while True:
                time.sleep(1.0)
        except KeyboardInterrupt:
            pass
    host.stop()


def main():
    ap = argparse.ArgumentParser(description="BandSync PC host")
    ap.add_argument("--headless", action="store_true")
    ap.add_argument("--simulate", action="store_true",
                    help="fake DAW clock instead of a MIDI port")
    ap.add_argument("--name", default="PC",
                    help="host name shown to phones")
    ap.add_argument("--bpm", default="104", help="tempo to broadcast")
    ap.add_argument("--tempo-map", default="",
                    help="MIDI file with the DAW tempo track — phones follow it")
    ap.add_argument("--play-after", default="2",
                    help="headless simulate: seconds before DAW play")
    ap.add_argument("--stop-after", default="8",
                    help="headless simulate: seconds of play before stop")
    args = ap.parse_args()
    if args.headless:
        run_headless(args)
    else:
        run_gui(args)


if __name__ == "__main__":
    main()
