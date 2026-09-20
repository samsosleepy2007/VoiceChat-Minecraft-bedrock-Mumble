# VC Mumble Endstone Plugin

Endstone side of **VC Mumble Server** proximity voice. Minecraft sends player position and voice settings to the Android Mumble server; audio remains inside Mumble.

## Current version

`0.4.0`

Target API: **Endstone 0.11**

## One command: /vcb

Players only need:

```text
/vcb
```

The command opens an **ActionForm** control panel. Normal players can change Voice Range, select distance-volume attenuation level, pair or unpair a Mumble username, and resync their current settings.

Operators with `vc_mumble.command.admin` also see **Admin Tools** in the same UI. The admin menu provides bridge status, player management, full resync, config reload, per-player Voice Range and per-player attenuation controls.

Legacy `/vcmumble` and `/vcmumbleadmin` are no longer registered.

## Distance-volume levels

Each player's outgoing voice has a persistent `attenuation_level`:

- **0 — Off:** full volume inside Voice Range, then cut at the boundary.
- **1 — Light:** gentle fade.
- **2 — Normal:** balanced default.
- **3 — Strong:** distant players become clearly quieter.
- **4 — Very Strong:** voices near the edge become extremely quiet.

The selected value is sent as `attenuationLevel` in each `player_state`. Android/native Mumble applies the factor independently for every speaker/listener pair.

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
default_attenuation_level = 2
```

On first enable, an empty bridge secret is generated and saved in `plugins/vc_mumble/config.toml`. Copy the same secret into VC Mumble Server on Android.

The bridge listens on TCP **27220**. Mumble voice continues to use TCP+UDP **64738**.
