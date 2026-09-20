# VC Mumble Server v0.6.0-beta.5

This beta adds the tested Minecraft Mic addon integration and microphone-aware proximity routing.

## Highlights

- Android app version 0.6.0-beta.5 (versionCode 10)
- Endstone plugin version 0.3.0
- Minecraft Item Mic addon version 2.7.4
- Mic ON/OFF state synchronizes through `vcmumble.mic.on` / `vcmumble.mic.off`
- Android/native routing consumes `voiceEnabled` and blocks regular proximity speech from a speaker whose mic is OFF
- Voice-range requests use `vcmumble.vr.request.*` with ACK plus server-value synchronization
- Default voice range is 30 blocks and follows the Endstone maximum
- Addon UI spacing was adjusted so the settings text is easier to read
- Legacy VoiceCraft item identifiers remain compatible so existing world items are preserved

## Release assets

- `VC-Mumble-Server-v0.6.0-beta.5-arm64-v8a.apk`
- APK SHA-256 checksum
- `endstone_vc_mumble-0.3.0-py3-none-any.whl`
- Endstone plugin SHA-256 checksum
- `VC_Mumble_ItemMic_v2.7.4.mcaddon`
- Minecraft addon SHA-256 checksum

## Minecraft proximity bridge

The Endstone plugin tracks identity, dimension, position, rotation, voice range and mic-enabled state. VC Mumble Server applies that state to per-listener proximity routing. Mumble users can remain in the Root channel.

Default ports:

- Mumble TCP+UDP: 64738
- Endstone bridge TCP: 27220

## Build provenance

GitHub Actions builds this release from `main` with pinned Mumble 1.6.870, Qt 6.8.3, OpenSSL 3.6.3, Android NDK 28.2.13676358 and vcpkg 2026.07.29.

The Minecraft addon source bundle is stored at `minecraft-addon/v2.7.4/source.tgz` and packaged into the release asset by the same release workflow.
