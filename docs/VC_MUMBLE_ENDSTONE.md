# VC Mumble Endstone protocol v1

VC Mumble uses a small authenticated bridge between the Endstone Minecraft server and the Android Mumble server.

## Topology

```text
Endstone plugin :27220/TCP  <-----  VC Mumble Server Android
       |
       +-- Minecraft player identity / dimension / position / voice range
```

The Mumble side can keep every user in the **Root** channel. Channel membership is not used to calculate proximity.


## Downloading the plugin from VC Mumble Server

The Android app can download and configure the Endstone wheel directly from this repository's GitHub Releases.

Each time **Download Plugin (.whl)** is pressed, the app performs a fresh GitHub Releases lookup. Drafts are ignored, while both stable and prerelease releases are eligible. The selected release is the newest published release that contains an `endstone_vc_mumble-*.whl` asset.

Before export, the app downloads `SHA256SUMS.txt` from the same release and verifies the original wheel. It then injects the Android app's current Bridge Secret into:

```text
endstone_vc_mumble/config.toml
```

The wheel's `.dist-info/RECORD` is rebuilt after the config change so the configured wheel remains internally consistent.

If the Android app does not yet have a Bridge Secret, it generates a 32-byte URL-safe secret and stores it through Android Keystore-backed `SecretStore`. The secret is never written to the app log.

For an Endstone server where the plugin is already installed, use **Download config.toml** and replace the server's existing:

```text
plugins/vc_mumble/config.toml
```

The exported wheel and config contain the shared secret in plaintext and should be handled as server credentials.

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
  "voiceRange": 30,
  "voiceEnabled": true,
  "attenuationLevel": 2
}
```

`mumbleName` defaults to the Minecraft player name unless the player explicitly pairs another name.

`voiceRange` belongs to the **speaker**. Android uses it when deciding how far that player's voice can be heard.

`attenuationLevel` is also speaker-owned: `0` disables fading inside the range, `1` is light, `2` normal, `3` strong, and `4` very strong. Missing values default to level `2` for compatibility.

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

Endstone sends speaker position, dimension, `voiceRange`, `voiceEnabled`, and `attenuationLevel`. The Android/native Mumble core applies the selected smooth falloff independently for every listener. Endstone never processes audio.

Use `/vcb` to open the ActionForm control panel and change range or attenuation without remembering subcommands. Operators get the admin tools in the same UI.

## Resync

Android may send:

```json
{"type":"request_snapshot"}
```

The plugin responds with a complete `sync_begin -> player_state* -> sync_end` snapshot.

Operators can request bridge/player resynchronization from the admin tools inside:

```text
/vcb
```
