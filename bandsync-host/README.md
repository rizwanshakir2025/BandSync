# BandSync Host (PC)

The PC side of BandSync. It hosts the session for the Android band members
**and** locks itself to your DAW's transport via MIDI Clock — press play in
Cubase and every phone joins the DAW's timeline, beat-aligned.

The host never plays audio itself. Your DAW plays through the PA; each phone
plays its own stems + click mix, synced to the DAW.

## How the DAW sync works

1. Cubase sends MIDI Clock (24 ppqn) + Start/Stop to a virtual MIDI port.
2. On DAW Start, this host broadcasts a transport command scheduled **1200 ms**
   in the future, with `startBeat` = the exact song position (in beats) the
   DAW will be at then. Phones seek their stems there instead of starting
   from the top — so they land on the DAW's timeline, not 1.2 s behind it.
3. On DAW Stop, it broadcasts stop. Phones stop.
4. Tick intervals are measured continuously; if the DAW's real tempo drifts
   from the expected tempo (>1 BPM), the host warns — restart the DAW
   transport to re-sync.

## Tempo-track following

Phones can follow the DAW's tempo track, not just a fixed tempo:

1. In Cubase, export the project's tempo track: File → Export → Tempo
   Track (or export a MIDI file containing the tempo events).
2. In the host GUI click **Load .mid…** (or `--tempo-map path.mid`).
   The host parses the tempo events into (beat → BPM) points and shows
   the count; the start tempo comes from the map, so the Tempo field is
   only a fallback when no map is loaded.
3. Every transport broadcast now carries `tempoMap`. Phones precompute the
   identical beat↔time function and switch the click + per-stem time-stretch
   exactly on tempo boundaries — all phones stay locked through every
   tempo change with no mid-song messages.
4. The drift monitor becomes map-aware: it compares the measured tick
   tempo against the tempo the map expects *at the DAW's current position*.

Assumption: the MIDI file's bar 1 = the DAW project's bar 1 (the normal
case when you export the project's own tempo track). Step tempo changes
come out clean; gradual ramps follow the DAW's own tempo events.

## Late join

A phone that connects while the DAW is already playing doesn't wait for
the next transport start: the host computes the DAW's current beat from
its own tick count and sends that phone a catch-up transport (with the
tempo map), so it seeks straight onto the moving timeline.

## Setup (Windows)

1. **Install Python 3.11+** from python.org (tick "Add python.exe to PATH").
2. **Install loopMIDI** (free, Tobias Erichsen) and create a port, e.g.
   `BandSync Clock`. Leave loopMIDI running.
3. **Cubase**: Studio → Studio Setup → MIDI → MIDI Clock Destinations —
   tick your loopMIDI port. (Or per-project: Transport → Project
   Synchronization Setup → MIDI Clock Out → enable + tick the port.)
   Pressing play in Cubase now emits MIDI Clock + Start/Stop on that port.
4. In this folder: `pip install -r requirements.txt`
5. **Songs**: put `song.json` files under `songs/<id>/song.json`
   (same format as the Android app; no WAVs needed on the PC).
   A demo entry is included. The **Tempo** in the app must match the
   Cubase project tempo (unless a tempo map is loaded — then the map
   supplies the tempo).
6. Run: `python bandsync_host.py`
   - Pick the MIDI port (your loopMIDI port), song, tempo → the status
     line shows the port is open.
   - On each phone: BandSync → Join → tap `BandSync-PC` (or enter the
     IP:port shown in the log) → each phone shows its live sync offset.
   - Press play in Cubase. Phones seek to the DAW's position and play.

No-DAW test: `python bandsync_host.py --simulate` gives you
"Simulate DAW play/stop" buttons — the full phone flow works with zero setup.

## Files

- `bandsync_host.py` — GUI + orchestration (`--headless`, `--simulate`)
- `midi_clock.py` — MIDI Clock → transport events (+ `SimulatedDaw`)
- `host_server.py` — TCP server for the phones (mirrors `HostServer.kt`)
- `protocol.py` — wire protocol (mirrors `Protocol.kt`)
- `songs.py` — `song.json` metadata loader
- `songs/demo_groove_100/song.json` — demo song metadata

## Protocol note

The `transport` play message carries `startBeat` (quarter-note beats at
`startHostMs`). Phone-hosted sessions send `startBeat: 0`; the PC host
sends the DAW's future position. The Android app seeks stems/clicks
accordingly (added in the same commit on the app side).

## v1 limits

- Without a tempo map, tempo is entered in the UI and must match the DAW
  project; deviations are warned about, not followed mid-song.
- No song-position seeking mid-play (jump the DAW → restart transport).
- `loop` is always off for DAW sessions — the DAW owns the timeline.
- MIDI Clock input jitter (±a few ms) does not affect phone alignment:
  phones align to the broadcast timestamps, not to packet arrival.
