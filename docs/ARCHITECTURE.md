# VC Mumble Server architecture

## Product goal

An Android host app that starts/stops a Mumble-compatible server in one tap. Voice clients remain stock Mumble/Mumla apps. Minecraft proximity rules are enforced server-side using authoritative player state from the standalone **VC Mumble Endstone Plugin**.

VC Mumble and VoiceCraft are separate projects.

## Data plane

```text
Minecraft Bedrock
      |
      v
VC Mumble Endstone Plugin
      |  TCP NDJSON :27220
      |  HMAC challenge/response
      v
VC Mumble Server Android app
      |
      +--> Native proximity state
      |
      +--> Embedded Mumble Server
             | TCP + UDP :64738
             v
        Stock Mumble clients
```

The phone connects **outbound** to the Endstone bridge. This avoids requiring the phone to accept an incoming Minecraft-state connection behind NAT/mobile data.

## Android lifecycle

- Android UI stores user configuration.
- A foreground service owns the user-started server lifecycle.
- The smoke build binds TCP+UDP only for lifecycle testing.
- Core mode embeds the prepared Mumble server through the QtService / Qt Android AAR path.
- Mumble configuration and SQLite data are kept in app-private storage.
- Stopping the embedded server returns control to Android instead of invoking upstream process-wide `exit()` behavior.

## Proximity routing

Maintain a native server-side map:

```text
Mumble username
  -> Minecraft XUID
  -> dimension + XYZ + voice range + freshness
```

When Mumble builds ordinary speech recipients inside `Server::processMsg()`, the prepared hook evaluates:

```text
paired sender
&& paired receiver
&& fresh state
&& same dimension
&& squaredDistance <= effectiveRange^2
```

The range policy does not require decoding or re-encoding Opus.

Proximity routing is fail-closed: when the bridge disconnects or authoritative player state becomes stale, Minecraft proximity does not silently fall back to a global voice room.

## Pairing

Pairing is owned by the Endstone plugin:

```text
/vcmumble pair <mumble_name>
```

The plugin streams `mumbleName` and `voiceRange` with each authoritative `player_state`; the Android app feeds those directly into the native routing table.

## Public hosting

The Minecraft-state bridge is easy across NAT because Android initiates it. The Mumble voice server is different: remote users must reach the Android phone over **both TCP and UDP** on the configured Mumble port. LAN works directly; public mobile-data hosting usually needs a future TCP+UDP relay/tunnel because of CGNAT.
