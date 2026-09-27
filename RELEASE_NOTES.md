# VC Mumble Server v0.6.0-beta.11

This release updates the Minecraft proximity stack tested with the MCSV-hosted custom Mumble server while preserving the existing Android VC Mumble Server and VC Mumla behavior.

## Highlights

- Android app version 0.6.0-beta.11 (versionCode 16)
- VC Mumla v0.5 AEC remains the proximity client and consumes the VCG1 per-listener gain trailer
- Endstone plugin updated to 0.4.2
- Minecraft Item Mic addon updated to 2.8.0
- Item Mic settings can control Voice Range and Distance Volume
- Distance Volume supports attenuation levels 0–4
- Endstone synchronizes and acknowledges addon range/attenuation requests
- Endstone sends Mumble identity, dimension, XYZ, voice range, Mic ON/OFF, and attenuation level
- Compatible with the custom MCSV MumbleHost v0.3.0 proximity core
- Existing Quick Join, AEC, saved servers, battery handling, MCSV installer, and forced-TCP behavior are preserved

## Release assets

- `VC-Mumble-Server-v0.6.0-beta.11-arm64-v8a.apk`
- `VC-Mumla-v0.5-aec-debug.apk`
- `endstone_vc_mumble-0.4.2-py3-none-any.whl`
- `VC_Mumble_ItemMic_v2.8.0.mcaddon`
- `SHA256SUMS.txt`

## Minecraft proximity flow

```text
Minecraft / Item Mic
        ↓
VC Mumble Endstone 0.4.2
        ↓ player_state
VC Mumble Server APK or MCSV MumbleHost v0.3.0
        ↓ proximity routing + per-listener VCG1 gain
VC Mumla v0.5
```

The Item Mic addon can change the speaker-owned voice range and the distance-volume attenuation level. The Endstone plugin validates those changes and immediately includes them in the state sent to the proximity server.

Smooth distance volume requires the custom VC Mumla client because unmodified Mumble clients do not consume the VCG1 gain trailer.

## Build provenance

GitHub Actions builds the Android release from `main` with pinned Mumble 1.6.870, Qt 6.8.3, Android NDK 28.2.13676358 and vcpkg 2026.07.29.

VC Mumla is built from pinned Mumla `477b337ebcee1655c1357d51db99cf92bbc175a4` and Humla `7966f3828d6ed87ef29c517abfade6ad7998cdc5`.

The Item Mic 2.8.0 release asset is reconstructed from the exact tested package with SHA-256 `2e5da0b7692383af9b836544e3324bb46cde6185c3419e0ad86de7d850aab522`.
