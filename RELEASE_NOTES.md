# VC Mumble Server v0.6.0-beta.6

This beta adds smooth distance-based voice attenuation to the native Mumble proximity routing.

## Highlights

- Android app version 0.6.0-beta.6 (versionCode 11)
- Endstone plugin version 0.3.0
- Minecraft Item Mic addon version 2.7.4
- Voice volume now decreases smoothly as players move farther apart
- 0–20% of speaker range stays at full volume
- 20–60% smoothly fades from 100% to about 55%
- 60–90% smoothly fades from about 55% to about 15%
- 90–100% smoothly fades from about 15% to about 3%
- Beyond the configured voice range, regular proximity speech is not routed
- Mic OFF and cross-dimension isolation continue to work as before
- Existing Mumble listener-volume adjustments are multiplied by the proximity factor instead of being overwritten
- Attenuation applies to normal, linked-channel, and channel-listener routing

## Release assets

- `VC-Mumble-Server-v0.6.0-beta.6-arm64-v8a.apk`
- `endstone_vc_mumble-0.3.0-py3-none-any.whl`
- `VC_Mumble_ItemMic_v2.7.4.mcaddon`
- `SHA256SUMS.txt` containing SHA-256 checksums for all three release assets

## Minecraft proximity bridge

The Endstone plugin tracks identity, dimension, position, rotation, voice range and mic-enabled state. VC Mumble Server applies that state per speaker/listener pair in the native Mumble routing path.

Mumble users can remain in the Root channel. The selected Minecraft voice range is speaker-owned and also controls the attenuation curve.

Default ports:

- Mumble TCP+UDP: 64738
- Endstone bridge TCP: 27220

## Build provenance

GitHub Actions builds this release from `main` with pinned Mumble 1.6.870, Qt 6.8.3, OpenSSL 3.6.3, Android NDK 28.2.13676358 and vcpkg 2026.07.29.

The Android core and Mumble source patch passed the dedicated branch CI before promotion to `main`.
