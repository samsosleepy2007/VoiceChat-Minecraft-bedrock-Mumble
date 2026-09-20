# VC Mumble Server v0.6.0-beta.4

This beta completes the first tested Android + Endstone proximity bridge milestone and fixes a server-start race discovered while editing Minecraft bridge settings.

## Highlights

- Android app version 0.6.0-beta.4 (versionCode 9)
- Endstone plugin version 0.2.0 included as a release asset
- Tested Android Mumble startup after editing Minecraft Server / Bridge settings
- Mumble startup no longer depends on the Minecraft bridge being reachable
- Minecraft bridge starts only after the local Mumble TCP listener is confirmed ready
- Synchronous persistence for server settings and encrypted Bridge Secret before launching the isolated Mumble process
- Clear service-dispatch logging when Android accepts or rejects a start request
- Authenticated Endstone TCP/NDJSON bridge with HMAC-SHA256 challenge/response
- Player identity, dimension, position and per-player voice range synchronization
- Automatic reconnect and fresh snapshot synchronization
- Duplicate initial snapshot synchronization removed
- Endstone operator commands for status, player inspection, resync, reload and range changes
- All Mumble users can remain in the Root channel; proximity is controlled by the server routing layer

## Android server

The embedded server remains:

- Mumble 1.6.870
- Qt 6.8.3
- OpenSSL 3.6.3
- Android ARM64-v8a
- Default Mumble TCP+UDP port 64738

The app performs a localhost TCP readiness probe before reporting ONLINE. A failed Endstone bridge connection does not block Mumble from starting.

## Minecraft proximity bridge

The Endstone plugin listens on TCP port 27220 by default. The Android app connects to it after Mumble is ready and authenticates with the shared Bridge Secret.

The bridge synchronizes:

- Minecraft player name
- XUID / UUID
- Mumble username mapping
- Dimension
- XYZ position
- Yaw / pitch
- Per-player voice range

Player commands:

```text
/vcmumble
/vcmumble status
/vcmumble pair <mumble_name>
/vcmumble unpair
/vcmumble range <blocks>
```

Operator commands:

```text
/vcmumbleadmin
/vcmumbleadmin status
/vcmumbleadmin players
/vcmumbleadmin resync
/vcmumbleadmin reload
/vcmumbleadmin range <player> <blocks>
```

The current native proximity routing uses distance/dimension state and the speaker's configured range. Smooth distance-based volume attenuation is not part of this release yet.

## Public access

VC Mumble Server does not embed PortWarp. For public Mumble access, use the PortWarp Android app and tunnel TCP+UDP to:

```text
127.0.0.1:64738
```

or the Mumble port configured in the app.

The Endstone bridge port must also be reachable from the Android device when the Minecraft server is hosted remotely.

## Background operation

The app uses a foreground service, partial CPU wake lock, persisted desired runtime state and periodic TCP health checks. Android 14+ uses the platform-managed Wi-Fi power mode instead of the legacy high-performance Wi-Fi lock.

## Release assets

The GitHub release publishes:

- `VC-Mumble-Server-v0.6.0-beta.4-arm64-v8a.apk`
- APK SHA-256 checksum
- `endstone_vc_mumble-0.2.0-py3-none-any.whl`
- Plugin SHA-256 checksum

## Build provenance

The release is built by GitHub Actions from `main` using pinned:

- Mumble 1.6.870
- Qt 6.8.3
- OpenSSL 3.6.3
- Android NDK 28.2.13676358
- vcpkg 2026.07.29

The release workflow rejects APKs containing an embedded `libpwrp` runtime.
