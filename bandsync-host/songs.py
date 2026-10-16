"""Song metadata loader. The Windows host never plays audio — it only needs
song.json (id, name, nativeBpm, beatsPerBar) so it can tell the phones
which song to play and at what stretch ratio."""

import json
import os


def load_songs(songs_dir):
    songs = []
    if not os.path.isdir(songs_dir):
        return songs
    for sid in sorted(os.listdir(songs_dir)):
        path = os.path.join(songs_dir, sid, "song.json")
        if not os.path.isfile(path):
            continue
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            stems = d.get("stems", [])
            songs.append({
                "id": sid,
                "name": d.get("name", sid),
                "nativeBpm": float(d.get("nativeBpm", 120.0)),
                "beatsPerBar": int(d.get("beatsPerBar", 4)),
                "stemNames": [s.get("name", "?") for s in stems],
            })
        except (ValueError, KeyError, TypeError):
            continue
    return songs
