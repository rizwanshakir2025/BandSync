"""DAW transport bridge via MIDI Clock.

The DAW (e.g. Cubase) sends MIDI Clock to a virtual port (loopMIDI):
  0xFA start, 0xFB continue, 0xFC stop, 0xF8 clock (24 ppqn), 0xF2 song position.

This module turns those into callbacks; it never touches the network itself,
so it is unit-testable with synthetic messages (see SimulatedDaw).

Timing model: on DAW start we do NOT know the tempo yet (clock ticks only
start flowing after 0xFA), so the app broadcasts with the user-confirmed
tempo from its UI. Tick intervals are measured continuously in the
background; if the DAW's real tempo drifts from the broadcast one, on_drift
fires and the UI warns (v1: no mid-song re-sync — restart the DAW transport).
"""

import threading
import time
from statistics import median


class MidiClockBridge:
    def __init__(self, on_start, on_stop, on_drift=None):
        """
        on_start(base_beats): DAW started; base_beats = song position in
            quarter-note beats (from the last Song Position Pointer, else 0).
        on_stop(): DAW stopped.
        on_drift(measured_bpm): fired (max once per play) when the measured
            tick tempo clearly disagrees with the broadcast tempo.
        """
        self._on_start = on_start
        self._on_stop = on_stop
        self._on_drift = on_drift
        self._ticks = []  # wall-clock ms of recent 0xF8 ticks
        self._lock = threading.Lock()
        self._spp_beats = 0.0
        self.playing = False
        self.broadcast_bpm = None  # set by the app when it broadcasts play
        self._drift_warned = False
        # Absolute tick counter + play-start anchor, for daw_beats().
        self.total_ticks = 0
        self._start_tick = 0
        self._start_base = 0.0
        # Optional () -> float: expected tempo right now (tempo-map aware).
        # When set, drift is measured against it instead of broadcast_bpm.
        self.expected_bpm_fn = None

    # -- input -----------------------------------------------------------

    def handle_message(self, msg):
        """Accepts mido.Message or any duck-typed object with .type / .pos."""
        now = time.time() * 1000.0
        t = msg.type
        if t == "clock":
            with self._lock:
                self._ticks.append(now)
                self.total_ticks += 1
                if len(self._ticks) > 96:
                    del self._ticks[:48]
        elif t == "start" or t == "continue":
            with self._lock:
                base = self._spp_beats
                self._spp_beats = 0.0
                self._ticks.clear()
                self._drift_warned = False
                self._start_tick = self.total_ticks
                self._start_base = base
                self.playing = True
            self._on_start(base)
        elif t == "stop":
            with self._lock:
                self.playing = False
                self._ticks.clear()
            self._on_stop()
        elif t == "songpos":
            # SPP counts 16th notes; convert to quarter-note beats
            with self._lock:
                self._spp_beats = msg.pos / 4.0

    # -- measurement -----------------------------------------------------

    def measured_bpm(self):
        """Median-based tempo from recent ticks; None if too few ticks."""
        with self._lock:
            ticks = list(self._ticks)
        if len(ticks) < 12:
            return None
        ivals = [b - a for a, b in zip(ticks[-48:], ticks[-47:])]
        ivals = [i for i in ivals if i > 0]
        if not ivals:
            return None
        med = median(ivals)
        if med <= 0:
            return None
        return 60000.0 / (24.0 * med)

    def check_drift(self):
        """Called periodically by the app while playing. Returns measured bpm
        when it disagrees with the expected tempo, else None."""
        if not self.playing or self._drift_warned:
            return None
        expected = (self.expected_bpm_fn() if self.expected_bpm_fn
                    else self.broadcast_bpm)
        if expected is None:
            return None
        m = self.measured_bpm()
        if m is not None and abs(m - expected) > 1.0:
            self._drift_warned = True
            if self._on_drift:
                self._on_drift(m)
            return m
        return None

    def daw_beats(self):
        """Song beats elapsed since the current play started
        (SPP base + ticks/24). None when not playing."""
        with self._lock:
            if not self.playing:
                return None
            return self._start_base + (self.total_ticks - self._start_tick) / 24.0


class _FakeMsg:
    def __init__(self, type, pos=0):
        self.type = type
        self.pos = pos


class SimulatedDaw:
    """Generates MIDI-clock traffic without hardware — for testing and for
    trying the host with zero setup (no loopMIDI/DAW needed)."""

    def __init__(self, bridge, bpm=104.0):
        self._bridge = bridge
        self.bpm = bpm
        self._stop_event = threading.Event()

    def play(self, beats=32):
        self._stop_event.clear()
        threading.Thread(target=self._run, args=(beats,), daemon=True,
                         name="sim-daw").start()

    def _run(self, beats):
        tick_ms = 60000.0 / self.bpm / 24.0
        self._bridge.handle_message(_FakeMsg("start"))
        total = int(beats * 24)
        for _ in range(total):
            if self._stop_event.is_set():
                return  # stop() already broadcast the stop
            self._bridge.handle_message(_FakeMsg("clock"))
            time.sleep(tick_ms / 1000.0)
        self._bridge.handle_message(_FakeMsg("stop"))

    def stop(self):
        self._stop_event.set()
        self._bridge.handle_message(_FakeMsg("stop"))
