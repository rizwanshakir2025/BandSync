# BandSync (working title)

One click for the whole band, over Wi-Fi. Any member hosts, picks the song
(stem set) and tempo, hits play — every connected phone plays the same stems
at the same tempo, each with its own local mix. Keeps playing with the screen
locked.

## How sync works

Stems are **pre-shared**: every phone holds the same WAV files, so the
network only carries tiny control messages. Each member phone:

1. Runs an NTP-style ping/pong against the host (~every 2 s) and keeps the
   median clock offset (jitter spikes rejected).
2. On Play, the host broadcasts `startHostMs` = host clock + 1500 ms.
   Every phone converts that to its own clock and schedules the first
   downbeat on its low-latency audio path.

Typical beat alignment on uncongested 5 GHz Wi-Fi: **5–20 ms**.
The app shows a live readout (offset / jitter / RTT) on each member phone.

## Tempo

Stems are time-stretched to the host tempo with the platform stretcher
(`AudioTrack.playbackParams.setSpeed`, pitch preserved). Ratio =
targetBpm / song.nativeBpm. Extreme changes (>±25%) will artifact —
that is a platform-stretcher limit, not a bug.

## Songs

`<app files>/songs/<song-id>/song.json` + one WAV per stem (16-bit PCM
recommended; 8/24/32-bit and other rates are converted on load):

```json
{"name": "My Song", "nativeBpm": 100.0, "beatsPerBar": 4,
 "stems": [{"name": "Kick", "file": "kick.wav"}]}
```

## Song library

The home screen's **Songs** button opens the library:

- **Import folder** — pick a folder; every WAV/MP3/M4A/FLAC inside becomes
  one song's stems. **Import files** — pick the stem files directly.
- Compressed formats are decoded once at import (Android's built-in
  decoder) and stored as 16-bit stereo 48 kHz WAV, so the playback engine
  never changes. You enter the song name and its **native BPM** (the tempo
  the stems were rendered at) — get this right, it's how the engine locks
  to the host.
- List shows name, BPM, stem count and size. **Rename** edits only the
  display name (the folder id never changes — it's the cross-phone join
  key). **BPM** fixes the native tempo / beats per bar. **Delete** asks for
  confirmation and refuses to delete the song that's currently playing.

Every member needs the same songs on their phone; matching is by song id.

## Tempo-map following (PC/DAW host)

When the PC host broadcasts a `tempoMap` (the DAW's tempo track, parsed
from an exported MIDI file), phones follow it: the click scheduler and the
per-stem time-stretch switch exactly on the map's tempo boundaries, so all
phones stay locked through tempo changes with no mid-song messages.
Step changes are clean; continuous ramps are approximated by the DAW's own
tempo events. Without a map, the session runs at the single broadcast tempo
(phone-hosted sessions always work this way).

## Late join

A phone that joins mid-song doesn't wait for the next Play: the host sends
it a catch-up transport for the current song position (recomputed from the
host's/DAW's own beat count), so it seeks straight onto the moving timeline.

## Keep-alive

Foreground service (`mediaPlayback`) + partial wake lock + high-perf Wi-Fi
lock. Tap *Allow playback with screen locked* on the home screen to exempt
BandSync from battery optimization.

## Build

Push to `main` → GitHub Actions builds the debug APK (same pipeline as
PresetPilot). Local builds need ~4 GB RAM for Gradle; the dev VM cannot
do it.

## Protocol (JSON lines over TCP)

`hi` → `welcome` → `ping`/`pong` loop → `transport {play|stop}`.
See `model/Protocol.kt`. Discovery is mDNS `_bandsync._tcp.`; the Join
screen also takes a manual IP:port for routers that block mDNS.

The `transport` play message carries `startBeat`: the song position in
quarter-note beats the timeline will be at when `startHostMs` arrives.
Phone-hosted sessions send `startBeat: 0`; the PC/DAW host
(`../bandsync-host/`) sends the DAW's future position so phones seek
their stems and click into the already-playing timeline instead of
starting late from the top.

## v1 limits (honest)

- WAV stems only (no MP3/AAC decode yet).
- Tempo changes restart the song from the top.
- Host sees member names, not their sync offsets.
- Inter-stem start jitter on one phone: ~1–3 ms (inaudible for stems).
