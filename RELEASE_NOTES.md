# VC Mumble Server v0.6.0-beta.12

This release adds realtime, private Voice Range preview to the Minecraft Item Mic while preserving the existing proximity audio stack.

## Highlights

- Android app version 0.6.0-beta.12 (versionCode 17)
- VC Mumla v0.5 AEC unchanged and continues consuming the VCG1 per-listener gain trailer
- Endstone plugin remains 0.4.2
- Minecraft Item Mic addon updated to 2.9.0
- Voice Range Slider now applies automatically while dragging; the old "ใช้ระยะจาก Slider" button is removed
- The current slider radius is previewed as a ring around the player in realtime
- The preview uses `Player.spawnParticle()`, so each player sees only their own Voice Range ring
- Other players do not see another player's preview ring
- The preview particle is intentionally short-lived so old radii disappear quickly while dragging
- Slider changes continue to use the existing Endstone range request/ACK contract, so the authoritative range stays synchronized with Mumble proximity routing
- Distance Volume levels 0–4 continue to work with MumbleHost v0.3.0 and the custom VC Mumla client

## Release assets

- `VC-Mumble-Server-v0.6.0-beta.12-arm64-v8a.apk`
- `VC-Mumla-v0.5-aec-debug.apk`
- `endstone_vc_mumble-0.4.2-py3-none-any.whl`
- `VC_Mumble_ItemMic_v2.9.0.mcaddon`
- `SHA256SUMS.txt`

## Voice Range preview flow

```text
DDUI Voice Range Slider
        ↓ live ObservableNumber
private preview ring (this player only)
        ↓
automatic vcmumble.vr.request
        ↓
Endstone 0.4.2 ACK / authoritative range
        ↓
MumbleHost v0.3.0 proximity routing
        ↓
VC Mumla VCG1 distance volume
```

The preview is visual only. Endstone remains authoritative for the actual accepted voice range.

## Compatibility

- Minecraft Bedrock / Endstone stack currently tested on the MCSV server with Item Mic BP 2.9.0 loaded successfully.
- MumbleHost v0.3.0 remains compatible; no MumbleHost protocol change is required.
- Smooth distance volume still requires the custom VC Mumla client because unmodified Mumble clients do not consume the VCG1 gain trailer.
