# VC Mumble Endstone protocol v1

VC Mumble uses a small authenticated bridge between the Endstone Minecraft server and the Android Mumble server.

## Topology

```text
Endstone plugin :27220/TCP  <-----  VC Mumble Server Android
       |
       +-- Minecraft player identity / dimension / position / voice range
```

The Mumble side can keep every user in the **Root** channel. Channel membership is not used to calculate proximity.

## Authentication

Transport is UTF-8 NDJSON over TCP.

The Android app begins with:

```json
{"type":"hello","role":"vc_mumble_server","protocol":1}
```

The plugin returns an `auth_challenge` containing a random nonce. Android replies with HMAC-SHA256 of:

```text
vc-mumble-v1:<nonce>
```

using the shared Bridge Secret.

The secret itself is never sent in the hello packet.

The plugin enforces an authentication timeout and rejects oversized frames.

## Initial synchronization

After successful authentication the plugin discards stale queued updates from any previous connection. The Android app then sends one explicit `request_snapshot`, so initial synchronization is not duplicated by both sides.

The stream is:

```text
sync_begin
player_state ...
player_state ...
sync_end
```

Android clears its native proximity state at the start of a new sync.

## Player state

A `player_state` message contains:

```json
{
  "type": "player_state",
  "name": "MinecraftName",
  "xuid": "...",
  "uuid": "...",
  "mumbleName": "MumbleName",
  "dimension": "Overworld",
  "x": 10.0,
  "y": 64.0,
  "z": 20.0,
  "yaw": 90.0,
  "pitch": 0.0,
  "voiceRange": 30
}
```

`mumbleName` defaults to the Minecraft player name unless the player explicitly pairs another name.

`voiceRange` belongs to the **speaker**. Android uses it when deciding how far that player's voice can be heard.

## Incremental updates

The plugin sends another `player_state` when position, rotation, dimension, identity or configured voice settings change enough to matter.

A disconnect sends:

```json
{"type":"player_leave", "...":"..."}
```

The periodic `heartbeat` carries online/tracked counts and also causes regular socket writes, helping dead connections fail instead of remaining silently stale.

## Dimension isolation

Different Minecraft dimensions are treated as separate voice spaces. Android should not route proximity voice between players whose `dimension` values differ.

## Distance attenuation

Protocol v1 already supplies the data required for future smooth attenuation:

- speaker position
- listener position
- dimension
- speaker `voiceRange`

The Endstone plugin does **not** process Mumble audio. Smooth volume falloff belongs on the Android/native Mumble side so one speaker can be loud for a nearby listener while simultaneously quiet for a distant listener.

## Resync

Android may send:

```json
{"type":"request_snapshot"}
```

The plugin responds with a complete `sync_begin -> player_state* -> sync_end` snapshot.

Operators can also request a snapshot with:

```text
/vcmumbleadmin resync
```
