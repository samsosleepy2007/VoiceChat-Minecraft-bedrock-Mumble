# VC Mumble Endstone Plugin

Endstone side of **VC Mumble Server** proximity voice. The plugin only tracks Minecraft player identity and position; audio stays inside Mumble.

## Current version

`0.2.0`

Target API:

`Endstone API 0.11`

## How it works

```text
Minecraft Bedrock players
        |
        | position / dimension / identity
        v
VC Mumble Endstone Plugin
        |
        | authenticated NDJSON/TCP :27220
        v
VC Mumble Server Android
        |
        | per-listener proximity routing
        v
Mumble Root channel
```

All Mumble users can stay in **Root**. The plugin does not create or move Mumble channels. The Android Mumble server decides who hears whom from Minecraft positions and each speaker's configured voice range.

## Features

- Tracks player name, XUID, UUID, dimension, XYZ and rotation every 2 ticks by default.
- Dimension and teleport changes are detected by the normal tracking snapshot.
- Authenticated TCP/NDJSON bridge with HMAC-SHA256 challenge/response.
- Full player snapshot after Android reconnect.
- Stale queued position updates are discarded on a fresh authenticated connection.
- TCP keepalive, auth timeout and frame-size protection.
- Automatic Minecraft-name to Mumble-name mapping by default.
- Optional explicit pairing with `/vcmumble pair <mumble_name>`.
- Persistent per-player voice range with `/vcmumble range <blocks>`.
- Operator administration commands for status, resync, reload and player range changes.
- Bindings are stored in the plugin data folder.

## Player commands

```text
/vcmumble
/vcmumble status
/vcmumble pair <mumble_name>
/vcmumble unpair
/vcmumble range <blocks>
```

If no explicit pairing exists, the Mumble username is assumed to be the Minecraft player name.

## Admin commands

Requires `vc_mumble.command.admin`, which defaults to operators.

```text
/vcmumbleadmin
/vcmumbleadmin status
/vcmumbleadmin players
/vcmumbleadmin resync
/vcmumbleadmin reload
/vcmumbleadmin range <player> <blocks>
```

## Default configuration

```toml
[tracking]
interval_ticks = 2
position_epsilon = 0.05
rotation_epsilon = 1.0
heartbeat_seconds = 15

[bridge]
enabled = true
host = "0.0.0.0"
port = 27220
secret = ""
max_queue = 4096
max_frame_bytes = 262144
auth_timeout_seconds = 10

[voice]
default_range = 30
max_range = 150
```

On first enable, an empty bridge secret is replaced with a generated secret and saved to:

```text
plugins/vc_mumble/config.toml
```

Copy that same secret into **VC Mumble Server** on Android.

## Installing

Build or download the wheel, then place it in the Endstone server's `plugins/` directory and restart Endstone.

Expected wheel name for this branch:

```text
endstone_vc_mumble-0.2.0-py3-none-any.whl
```

## Network direction

The Endstone plugin listens on TCP port `27220`. The Android app initiates the connection to the Minecraft server's reachable IP/hostname and authenticates using the shared Bridge Secret.

This bridge is separate from Mumble's normal TCP/UDP port `64738`.
