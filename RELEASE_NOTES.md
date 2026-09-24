# VC Mumble Server v0.6.0-beta.8

This beta adds the in-app setup flow for Endstone/MCSV while keeping the tested Minecraft proximity voice stack from beta.7.

## Highlights

- Android app version 0.6.0-beta.8 (versionCode 13)
- VC Mumla client v0.4 Stable Gain
- Endstone plugin version 0.4.1
- Minecraft Item Mic addon version 2.7.6
- In-app latest Endstone Plugin download with SHA-256 verification and Bridge Secret injection
- In-app latest Item Mic Addon download with SHA-256 verification
- Optional MCSV one-click setup now installs the complete VC Mumble server-side stack:
  - configured Endstone wheel + `plugins/vc_mumble/config.toml`
  - latest Item Mic `.mcaddon`
  - nested Behavior Pack + Resource Pack
  - active-world `world_behavior_packs.json` and `world_resource_packs.json` references
- MCSV installer preserves unrelated Bedrock packs and adds rollback for managed pack folders/world pack JSON
- If the MCSV server is running, setup stops it before modifying Bedrock world files and restores the previous running state afterward
- MCSV API key is used only for the install request and is not persisted
- First launch shows a red Endstone requirement notice with MCSV setup steps; the guide remains available from Settings
- MCSV and Endstone artwork are embedded as validated WebP assets with SHA-256/dimension contract checks
- Android bridge fragmented-NDJSON handling remains protected by regression tests
- Existing proximity features remain: Mic ON/OFF sync, dimension isolation, range filtering, per-listener attenuation and VC Mumla gain support

## Release assets

- `VC-Mumble-Server-v0.6.0-beta.8-arm64-v8a.apk`
- `VC-Mumla-v0.4-stable-gain-debug.apk`
- `endstone_vc_mumble-0.4.1-py3-none-any.whl`
- `VC_Mumble_ItemMic_v2.7.6.mcaddon`
- `SHA256SUMS.txt` containing SHA-256 checksums for all four release binaries

## MCSV one-click install

For an MCSV-hosted Minecraft Bedrock server running Endstone, paste an MCSV API key in the green MCSV card and press **ติดตั้ง VC Mumble ผ่าน MCSV**.

The app validates the server type, selects an allocated non-game bridge port, resolves the newest compatible Plugin and Addon from GitHub Releases, verifies both against `SHA256SUMS.txt`, installs the Plugin/Addon configuration, updates the active world pack references, and restores the server runtime state.

For a custom-permission MCSV key, enable:

`server_info`, `server_resources`, `domain_info`, `files_list`, `files_read`, `files_upload_base64`, `files_mkdir`, `files_decompress`, `files_rename`, `files_delete`, `files_write`, `power_action`.

## Minecraft proximity bridge

Endstone sends Mumble identity, dimension, XYZ position, speaker-owned voice range, Mic ON/OFF state, and attenuation level to the Android server over the authenticated NDJSON bridge.

The native Mumble routing layer resolves the speaker/listener pair, checks dimension/range/mic state, calculates the attenuation factor, and sends it to VC Mumla using the backward-compatible VC gain trailer.

Default ports:

- Mumble TCP+UDP: 64738
- Endstone bridge TCP: 27220

## Compatibility note

Smooth distance volume requires VC Mumla v0.4 Stable Gain. Unmodified legacy Mumble clients do not consume the VC per-listener gain metadata.

The Minecraft proximity integration requires a Bedrock server running Endstone.

## Build provenance

GitHub Actions builds this release from `main` with pinned Mumble 1.6.870, Qt 6.8.3, OpenSSL 3.6.3, Android NDK 28.2.13676358 and vcpkg 2026.07.29.

VC Mumla is built from pinned Mumla `477b337ebcee1655c1357d51db99cf92bbc175a4` and Humla `7966f3828d6ed87ef29c517abfade6ad7998cdc5`.
