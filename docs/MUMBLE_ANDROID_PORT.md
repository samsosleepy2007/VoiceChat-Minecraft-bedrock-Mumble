# Mumble server Android port plan

## Goal

Cross-compile the upstream Mumble server for Android ARM64 while preserving compatibility with unmodified Mumble/Mumla clients. Android is the host; Minecraft state comes from the standalone VC Mumble Endstone Plugin.

For the first server build keep the upstream dependency surface small:

```text
client=OFF
server=ON
plugins=OFF
tests=OFF
zeroconf=OFF
ice=OFF
```

Ice can be reconsidered later for administration; it is not required for proximity routing.

## Native dependency surface

The server build needs Qt server-side modules plus its cryptographic/protocol/database dependencies, including:

- Qt 6 Core
- Qt 6 Network
- Qt 6 XML
- Qt 6 SQL / SQLite driver
- OpenSSL
- Protocol Buffers

The core build is not considered complete merely because `libvcserver.so` links. The generated Qt AAR must also contain the runtime/plugin pieces required on a real Android device.

## Android integration

```text
Android UI
    |
    v
Foreground QtService
    |
    v
Qt Android runtime AAR
    |
    v
Prepared upstream Mumble server
    +-- TCP Mumble control
    +-- UDP Mumble voice
```

The app's smoke mode remains separate so ordinary Android UI/service development does not require a complete Qt/Mumble toolchain.

## Minecraft state transport

The project no longer reuses VoiceCraft and does not require a WebSocket relay for the MVP.

```text
VC Mumble Server APK
       |
       | outbound TCP NDJSON
       | HMAC-SHA256 challenge/response
       v
VC Mumble Endstone Plugin :27220
       |
       v
Minecraft Bedrock player state
```

The phone initiates the connection. This works naturally when the phone itself is behind home NAT or mobile carrier NAT because no incoming Minecraft-state bridge port is required on Android.

The plugin streams server-authoritative identity, dimension, XYZ and per-player range. Mumble clients are never trusted as the source of Minecraft coordinates.

## Proximity routing

The prepared Mumble server keeps:

```text
Mumble username
 -> Minecraft XUID
 -> dimension, x, y, z, effectiveRange, freshness
```

Ordinary speech recipients are gated while `Server::processMsg()` builds its receiver set:

```text
stateFresh(sender)
&& stateFresh(receiver)
&& sameDimension(sender, receiver)
&& squaredDistance(sender, receiver) <= effectiveRange^2
```

No Opus decode/re-encode is necessary for simple receiver filtering.

Missing/stale state fails closed when Minecraft proximity is enabled so a bridge outage cannot accidentally turn the server into a global proximity room.

## Public voice hosting

The direct Endstone bridge and Mumble voice reachability are different problems:

- **Endstone bridge:** Android connects outbound to the Minecraft server; NAT is normally fine.
- **Mumble voice:** remote clients must reach the phone on both TCP and UDP at the configured Mumble port.

LAN hosting can work directly. Public mobile-data hosting commonly needs a future TCP+UDP relay/tunnel because of CGNAT.

## Definition of done for the Android Mumble core

- stock Mumble/Mumla authenticates to the APK;
- stock client joins Root;
- two clients exchange real voice;
- server survives background/screen-off under the foreground service;
- Stop closes sockets/database cleanly;
- Start works again;
- standalone Endstone bridge authenticates;
- player movement/dimension changes affect receiver routing live.
