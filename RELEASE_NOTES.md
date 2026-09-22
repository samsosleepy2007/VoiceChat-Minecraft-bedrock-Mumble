# VC Mumble Server v0.6.0-beta.7

This beta promotes the tested Minecraft proximity voice stack, including the custom VC Mumla client required for smooth per-listener distance volume.

## Highlights

- Android app version 0.6.0-beta.7 (versionCode 12)
- VC Mumla client v0.4 Stable Gain
- Endstone plugin version 0.4.1
- Minecraft Item Mic addon version 2.7.6
- Single /vcb ActionForm control panel for player and admin voice settings
- Per-player distance attenuation levels 0-4
- Smooth per-listener distance volume through the VC legacy gain extension
- Proximity freshness now follows bridge heartbeat instead of player movement
- VC Mumla keeps the stable v0.2 TCP transport behavior while applying server gain after decode
- Fixed Mic item ON being reported as speaker-mic-off
- Endstone now prefers vcmumble.mic.on when stale ON/OFF tags conflict and removes the stale OFF tag
- Addon Mic tag publishing now verifies mutually-exclusive ON/OFF tags and repairs them with a command fallback
- Native proximity diagnostics report tracked state and routing reasons for troubleshooting
- Different dimensions and players beyond voice range are not routed

## Release assets

- `VC-Mumble-Server-v0.6.0-beta.7-arm64-v8a.apk`
- `VC-Mumla-v0.4-stable-gain-debug.apk`
- `endstone_vc_mumble-0.4.1-py3-none-any.whl`
- `VC_Mumble_ItemMic_v2.7.6.mcaddon`
- `SHA256SUMS.txt` containing SHA-256 checksums for all four release binaries

## Minecraft proximity bridge

Endstone sends Mumble identity, dimension, XYZ position, speaker-owned voice range, Mic ON/OFF state, and attenuation level to the Android server over the authenticated NDJSON bridge.

The native Mumble routing layer resolves the speaker/listener pair, checks dimension/range/mic state, calculates the attenuation factor, and sends it to VC Mumla using the backward-compatible VC gain trailer.

Default ports:

- Mumble TCP+UDP: 64738
- Endstone bridge TCP: 27220

## Compatibility note

Smooth distance volume requires VC Mumla v0.4 Stable Gain. Unmodified legacy Mumble clients do not consume the VC per-listener gain metadata.

## Build provenance

GitHub Actions builds this release from `main` with pinned Mumble 1.6.870, Qt 6.8.3, OpenSSL 3.6.3, Android NDK 28.2.13676358 and vcpkg 2026.07.29.

VC Mumla is built from pinned Mumla `477b337ebcee1655c1357d51db99cf92bbc175a4` and Humla `7966f3828d6ed87ef29c517abfade6ad7998cdc5`.
