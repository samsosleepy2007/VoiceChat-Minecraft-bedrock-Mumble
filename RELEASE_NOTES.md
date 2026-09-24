# VC Mumble Server v0.6.0-beta.9

This is a minor update focused on VC Mumla audio echo handling. The server, Endstone plugin, Minecraft Addon, MCSV installer, and proximity routing behavior remain compatible with beta.8.

## Highlights

- Android app version 0.6.0-beta.9 (versionCode 14)
- VC Mumla client v0.5 AEC
- Endstone plugin version 0.4.1
- Minecraft Item Mic addon version 2.7.6
- Android System Acoustic Echo Cancellation is enabled by default in VC Mumla when the device supports it
- Existing VC Mumla installs receive a one-time migration from the old default `none` to `system` AEC when Android reports AEC support
- System AEC uses `MODE_IN_COMMUNICATION` with `VOICE_COMMUNICATION` microphone input
- VC Mumla logs AEC availability, creation, enabled state, control state, status, audio session and input source for diagnostics
- AudioManager mode is restored when disconnecting and also if microphone initialization fails
- Devices without Android AcousticEchoCanceler support safely fall back to no system AEC
- Existing VC per-listener distance gain and forced-TCP transport behavior are preserved
- Existing MCSV one-click Plugin + Addon installation from beta.8 is unchanged

## Release assets

- `VC-Mumble-Server-v0.6.0-beta.9-arm64-v8a.apk`
- `VC-Mumla-v0.5-aec-debug.apk`
- `endstone_vc_mumble-0.4.1-py3-none-any.whl`
- `VC_Mumble_ItemMic_v2.7.6.mcaddon`
- `SHA256SUMS.txt` containing SHA-256 checksums for all four release binaries

## VC Mumla AEC behavior

When System AEC is selected and supported by Android, VC Mumla switches the capture path to `MediaRecorder.AudioSource.VOICE_COMMUNICATION` and places Android audio in `MODE_IN_COMMUNICATION` while voice is active. The Android `AcousticEchoCanceler` is attached to the active AudioRecord session.

The previous Android audio mode is restored when the voice stack shuts down. If microphone creation fails after communication mode has been entered, VC Mumla also restores the previous mode before propagating the error.

If a device reports that `AcousticEchoCanceler` is unavailable, VC Mumla falls back to the non-AEC path instead of failing voice capture.

## Minecraft proximity bridge

Endstone sends Mumble identity, dimension, XYZ position, speaker-owned voice range, Mic ON/OFF state, and attenuation level to the Android server over the authenticated NDJSON bridge.

The native Mumble routing layer resolves the speaker/listener pair, checks dimension/range/mic state, calculates the attenuation factor, and sends it to VC Mumla using the backward-compatible VC gain trailer.

Default ports:

- Mumble TCP+UDP: 64738
- Endstone bridge TCP: 27220

## Compatibility note

Smooth distance volume and the VC per-listener gain trailer require VC Mumla. Unmodified legacy Mumble clients do not consume the VC gain metadata.

AEC quality still depends on the Android device/OEM audio implementation. VC Mumla v0.5 AEC keeps the manual Echo Cancellation setting available for devices where system AEC behaves poorly.

The Minecraft proximity integration requires a Bedrock server running Endstone.

## Build provenance

GitHub Actions builds this release from `main` with pinned Mumble 1.6.870, Qt 6.8.3, OpenSSL 3.6.3, Android NDK 28.2.13676358 and vcpkg 2026.07.29.

VC Mumla is built from pinned Mumla `477b337ebcee1655c1357d51db99cf92bbc175a4` and Humla `7966f3828d6ed87ef29c517abfade6ad7998cdc5`.
