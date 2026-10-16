"""BandSync wire protocol — JSON lines over TCP. Mirrors model/Protocol.kt.

Client -> host:  hi {name}
Host -> client:  welcome {hostName}
Client -> host:  ping {t1}            (t1 = client wall-clock ms)
Host -> client:  pong {t1, t2, t3}    (t2 = host recv ms, t3 = host send ms)
Host -> all:     transport {state, songId, songName, nativeBpm, targetBpm,
                            beatsPerBar, countInBars, loop,
                            startHostMs, startBeat}
Either -> other: bye {}

startBeat: song position in quarter-note beats that the DAW/host will be at
when startHostMs arrives. Phones seek their stems there instead of starting
from the top — this is what lets a DAW that is already playing stay aligned
with phones that join its timeline late.
"""

VERSION = 1


def hi(name):
    return {"type": "hi", "v": VERSION, "name": name}


def welcome(host_name):
    return {"type": "welcome", "v": VERSION, "hostName": host_name}


def pong(t1, t2, t3):
    return {"type": "pong", "t1": t1, "t2": t2, "t3": t3}


def transport_play(song_id, song_name, native_bpm, target_bpm, beats_per_bar,
                   count_in_bars, loop, start_host_ms, start_beat=0.0):
    return {
        "type": "transport",
        "state": "play",
        "songId": song_id,
        "songName": song_name,
        "nativeBpm": native_bpm,
        "targetBpm": target_bpm,
        "beatsPerBar": beats_per_bar,
        "countInBars": count_in_bars,
        "loop": loop,
        "startHostMs": int(start_host_ms),
        "startBeat": float(start_beat),
    }


def transport_stop():
    return {"type": "transport", "state": "stop"}


def bye():
    return {"type": "bye"}


def parse_transport(msg):
    if not isinstance(msg, dict) or msg.get("type") != "transport":
        return None
    return {
        "state": msg.get("state", ""),
        "songId": msg.get("songId", ""),
        "songName": msg.get("songName", ""),
        "nativeBpm": float(msg.get("nativeBpm", 0) or 0),
        "targetBpm": float(msg.get("targetBpm", 0) or 0),
        "beatsPerBar": int(msg.get("beatsPerBar", 4) or 4),
        "countInBars": int(msg.get("countInBars", 0) or 0),
        "loop": bool(msg.get("loop", False)),
        "startHostMs": int(msg.get("startHostMs", 0) or 0),
        "startBeat": float(msg.get("startBeat", 0.0) or 0.0),
    }
