# VC Mumble Server v0.6.0-beta.10

This release focuses on making VC Mumla faster and easier to join while keeping the beta.9 AEC, proximity, Endstone, Addon, and MCSV behavior compatible.

## Highlights

- Android app version 0.6.0-beta.10 (versionCode 15)
- VC Mumla client v0.5 AEC with Quick Join improvements
- Endstone plugin version 0.4.1
- Minecraft Item Mic addon version 2.7.6
- Quick Join accepts a single `host:port` value such as `4dqruj3i.free.pwrp.cc:10027` and splits host/port automatically
- Xbox username is required in Quick Join and is clearly highlighted because it must match the Minecraft/Xbox identity used by the proximity bridge
- Server password is hidden initially; VC Mumla prompts for it only when the Mumble server rejects the connection with a password error
- Successful Quick Join servers are saved automatically so the user does not need to enter the same connection again
- Saved server data keeps host, port, Xbox username, server password when required, and the server name learned from VC Mumble Server
- VC Mumla learns the configured VC Mumble Server name from the server welcome text after connection
- Unrestricted-battery guidance is localized to Thai and remains available in VC Mumla settings
- Android System Acoustic Echo Cancellation remains enabled by default when supported
- Existing per-listener distance gain and forced-TCP behavior are preserved

## Release assets

- `VC-Mumble-Server-v0.6.0-beta.10-arm64-v8a.apk`
- `VC-Mumla-v0.5-aec-debug.apk`
- `endstone_vc_mumble-0.4.1-py3-none-any.whl`
- `VC_Mumble_ItemMic_v2.7.6.mcaddon`
- `SHA256SUMS.txt`

## VC Mumla Quick Join

Quick Join is intended for tunnel addresses commonly shared as one value:

```text
4dqruj3i.free.pwrp.cc:10027
```

VC Mumla parses the hostname and port automatically. The Xbox username field is mandatory because the proximity bridge maps Minecraft players to Mumble users by identity.

The password field is not shown before the first connection attempt. If the target Mumble server does not require a password, the connection proceeds normally. If the server returns `WrongServerPW` or `WrongUserPW`, VC Mumla opens a Thai password prompt and reconnects using the same host, port, and Xbox username.

After a successful connection, the server is added to the saved server list. Reconnecting from the saved entry no longer requires entering the same connection information again.

## VC Mumla background audio and AEC

VC Mumla can request Android's unrestricted-battery / ignore battery optimizations permission so voice is less likely to be stopped while Minecraft is in the foreground or the screen is off.

The app-owned battery guidance is localized to Thai. Android's own exemption confirmation remains controlled by the device/system language.

When System AEC is selected and supported, VC Mumla uses `MODE_IN_COMMUNICATION`, `VOICE_COMMUNICATION`, and Android's `AcousticEchoCanceler`. Devices without system AEC support continue to fall back safely.

## Minecraft proximity bridge

Endstone sends Mumble identity, dimension, XYZ position, speaker-owned voice range, Mic ON/OFF state, and attenuation level to VC Mumble Server over the authenticated NDJSON bridge.

The native Mumble routing layer resolves speaker/listener pairs, checks dimension/range/mic state, calculates attenuation, and sends the per-listener gain to VC Mumla.

Default ports:

- Mumble TCP+UDP: 64738
- Endstone bridge TCP: 27220

The Minecraft proximity integration requires a Bedrock server running Endstone.

## Compatibility

- VC Mumble Server beta.10 remains compatible with Endstone plugin 0.4.1 and Item Mic addon 2.7.6.
- Smooth distance volume requires VC Mumla because unmodified legacy Mumble clients do not consume the VC gain trailer.
- AEC quality depends on the Android device/OEM audio implementation.

## Build provenance

GitHub Actions builds this release from `main` with pinned Mumble 1.6.870, Qt 6.8.3, OpenSSL 3.6.3, Android NDK 28.2.13676358 and vcpkg 2026.07.29.

VC Mumla is built from pinned Mumla `477b337ebcee1655c1357d51db99cf92bbc175a4` and Humla `7966f3828d6ed87ef29c517abfade6ad7998cdc5`.
