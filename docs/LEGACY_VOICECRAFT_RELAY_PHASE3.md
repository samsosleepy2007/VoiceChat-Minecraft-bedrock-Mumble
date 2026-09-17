# VoiceCraft Endstone relay integration

Phase 3 reuses the existing VoiceCraft Endstone state plane instead of installing a second Minecraft tracking plugin.

## Roles

The Endstone plugin authenticates to the relay as:

```json
{"type":"hello","role":"endstone","serverId":"...","secret":"...","protocol":1}
```

VC Mumble Server authenticates to the same relay as the Android peer:

```json
{"type":"hello","role":"android","serverId":"...","secret":"...","protocol":1}
```

The app never hardcodes or logs the bridge secret. The user enters it once and the Android app encrypts it at rest with an AES-GCM key held by Android Keystore.

## State messages consumed by the APK

The existing Endstone plugin already sends:

- `player_state` — Minecraft name/XUID/world/dimension/position/rotation
- `player_leave` — remove stale identity
- `sync_begin` / `sync_end` — atomic full-state refresh boundaries
- `heartbeat` — tracker liveness

After `hello_ok`, the APK sends `request_snapshot` so it does not need to wait for every player to move before proximity becomes usable.

## Phase-3 identity rule

For the first end-to-end test, the Mumble username must equal the Minecraft player name (case-insensitive).

```text
Minecraft: Sam4014XD
Mumble:    Sam4014XD
```

A durable XUID-to-Mumble-user binding table is a later milestone. The existing `/vcbind` workflow is deliberately not repurposed until stock-client login and audio routing are proven.

## Failure behavior

When Minecraft proximity is enabled, the native receiver filter is enabled immediately but starts with an empty state table. This is intentional **fail-closed** behavior:

- relay authenticated + fresh state: route by dimension/range
- relay disconnected: clear player state; regular proximity speech has no eligible remote receivers
- stale player state: withhold regular speech for that pair
- proximity disabled: preserve stock Mumble routing

This avoids accidentally turning a proximity server into a global room during a relay outage.

Whisper/shout routing is left outside the Phase-3 regular-speech filter so it can later become radio/admin/explicit-target semantics.
