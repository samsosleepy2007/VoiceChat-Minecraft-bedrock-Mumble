# VC Mumble Endstone Plugin

Standalone Endstone side of **VC Mumble Server**. It does not depend on or modify VoiceCraft.

## Features

- Tracks player name, XUID, UUID, dimension, XYZ and rotation every 2 ticks by default.
- Opens a direct TCP/NDJSON bridge for the Android VC Mumble Server app.
- HMAC-SHA256 nonce challenge/response; the bridge secret is not sent in the hello packet.
- Sends full snapshots on connect and incremental position updates afterwards.
- Explicit Mumble identity pairing with `/vcmumble pair <mumble_name>`.
- Per-player voice range with `/vcmumble range <blocks>`.
- Keeps binding data in its own plugin data folder.

## Commands

```text
/vcmumble
/vcmumble status
/vcmumble pair <mumble_name>
/vcmumble unpair
/vcmumble range <blocks>
```

## Test-server defaults

```text
TCP bridge port: 27220
Protocol: 1
Default range: 30 blocks
Maximum range: 150 blocks
```

The bridge secret is generated automatically on first enable and saved to:

```text
plugins/vc_mumble/config.toml
```

The Android app initiates the connection, so the phone does not need to accept an incoming Minecraft-state bridge connection.

### Endstone 0.11.10 packaging compatibility note

The currently deployed server runtime reports plugin runtime version **0.1.1**. Its tested wheel filename/distribution metadata remains `0.1.0` as a compatibility slot for the server's legacy Python wheel loader. The code inside that tested wheel contains the v0.1.1 HMAC authentication update.
