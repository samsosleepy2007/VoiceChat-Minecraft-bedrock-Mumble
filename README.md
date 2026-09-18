# VC Mumble Server

Standalone Android-hosted Mumble-compatible server for Minecraft Bedrock proximity voice chat.

**VC Mumble Server is a separate project from VoiceCraft.** It has its own Endstone plugin, bridge protocol, pairing data and Android app. The long-term client goal stays simple: players use normal Mumble/Mumla clients while Minecraft-aware routing is enforced by the server.

## Current status — Phase 4 standalone bridge

Implemented and tested in source:

- one-tap **Quick Start** Android UI
- configurable Mumble server name / port / password / max users
- configurable Minecraft voice range
- Android foreground-service lifecycle
- Android Keystore + AES-GCM storage for the bridge secret
- direct Android -> Endstone TCP bridge; no VoiceCraft relay dependency
- HMAC-SHA256 challenge-response authentication; the shared secret is not sent in the hello packet
- standalone Endstone plugin with `/vcmumble` commands
- authoritative player name / XUID / dimension / XYZ / rotation tracking
- explicit Minecraft XUID -> Mumble username pairing
- per-player voice range in the position stream
- snapshot + incremental state synchronization
- native proximity state table with same-dimension, range and stale-state checks
- regular-speech receiver hook prepared for Mumble `Server::processMsg()`
- pinned Mumble **1.6.870** source preparation
- QtService / Qt AAR embedding path for the Android Mumble server core
- fallback TCP+UDP Android transport smoke test

Verified on the project MCSV Endstone 0.11.10 test server:

- `VC Mumble Endstone v0.1.1` loads beside the existing VoiceCraft plugin without modifying it
- bridge binds on `0.0.0.0:27220/TCP`
- `/vcmumble status` responds successfully

**Not claimed yet:** the full Mumble 1.6.870 Android core has not yet passed the final physical-device gate where stock Mumble/Mumla clients log in and exchange real voice through the APK. Until that passes, core mode remains experimental.

## Default topology

```text
Minecraft Bedrock
      |
      v
VC Mumble Endstone Plugin
TCP :27220
      ^
      | authenticated NDJSON + HMAC challenge/response
      |
VC Mumble Server APK
      |
      +-- Mumble TCP :64738
      +-- Mumble UDP :64738
      |
      v
Stock Mumble / Mumla clients
```

## Defaults

| Setting | Default |
| --- | --- |
| Mumble server name | Minecraft Voice |
| Mumble port | 64738 TCP + UDP |
| Max users | 20 |
| Proximity range | 30 blocks |
| Endstone bridge port | 27220 TCP |
| Proximity | Off until explicitly enabled |

No real bridge secret is included in this repository or APK source.

## Endstone plugin

The standalone plugin lives in [`endstone-plugin/`](endstone-plugin/).

Commands:

```text
/vcmumble
/vcmumble status
/vcmumble pair <mumble_name>
/vcmumble unpair
/vcmumble range <blocks>
```

On first enable it generates a bridge secret and saves it in:

```text
plugins/vc_mumble/config.toml
```

Copy that value into the VC Mumble Server app. Do not publish it.

The test server uses:

```text
Minecraft: sv5.mcsv.me:10459
VC Mumble bridge: sv5.mcsv.me:27220 TCP
```

See [`docs/SETUP.md`](docs/SETUP.md) and [`docs/VC_MUMBLE_ENDSTONE.md`](docs/VC_MUMBLE_ENDSTONE.md).

## Normal Android build

Requirements:

- JDK 17
- Android SDK 36
- Android Build Tools 36.0.0
- Android NDK 28.2.13676358
- CMake 4.1.0
- Gradle 9.6.0

```bash
gradle :app:assembleDebug
```

The normal build intentionally uses the lightweight TCP+UDP smoke-test server. It validates Android UI/service/network lifecycle but is **not** a Mumble protocol server.

## Embedded Mumble core build

Prepare the pinned upstream source:

```bash
./scripts/fetch-mumble.sh
```

Build the Android core and package the APK:

```bash
./scripts/build-mumble-android-core.sh
gradle -PvcMumbleCore=true :app:assembleDebug
```

The manual **Android Embedded Mumble Core (experimental)** GitHub Actions workflow provisions Qt and native dependencies, builds the prepared server core/AAR path, packages the APK and uploads build artifacts for validation.

## Required validation gate

A release is not considered Mumble-compatible until all of these pass on a physical Android ARM64 device:

```text
1. Start VC Mumble Server
2. Stock Mumble/Mumla client connects
3. Client authenticates and joins Root
4. A second stock client joins
5. Both clients exchange voice over the normal Mumble TCP/UDP protocol
6. Endstone bridge connects
7. /vcmumble pair maps both Minecraft players to Mumble identities
8. Moving outside voice range removes the receiver
9. Returning inside range restores the receiver
10. Changing Minecraft dimension isolates voice immediately
```

## Documentation

- [`docs/SETUP.md`](docs/SETUP.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/VC_MUMBLE_ENDSTONE.md`](docs/VC_MUMBLE_ENDSTONE.md)
- [`docs/MUMBLE_ANDROID_PORT.md`](docs/MUMBLE_ANDROID_PORT.md)
- [`docs/MUMBLE_CORE_BUILD.md`](docs/MUMBLE_CORE_BUILD.md)
- [`docs/ROADMAP.md`](docs/ROADMAP.md)
