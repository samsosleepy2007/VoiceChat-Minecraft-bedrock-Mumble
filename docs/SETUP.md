# VC Mumble Server setup

This setup is standalone and does not use VoiceCraft.

## 1. Install the Endstone plugin

Copy the built wheel from `endstone-plugin/dist/` into the Endstone `plugins/` directory and restart the Minecraft server.

Expected startup lines include:

```text
[VCMumble] Enabling vc_mumble v0.1.1
[VCMumble] BRIDGE listening tcp=0.0.0.0:27220 protocol=1
```

Check from console or in game:

```text
/vcmumble status
```

## 2. Get the bridge secret

On first successful enable the plugin creates:

```text
plugins/vc_mumble/config.toml
```

Copy the value under `[bridge] secret` into the Android app. Treat it like a password. The source repository deliberately contains no real secret.

Authentication uses a nonce + HMAC-SHA256 challenge/response, so the secret itself is not transmitted in the initial handshake.

## 3. Configure VC Mumble Server on Android

For the current MCSV test server enter:

```text
Minecraft bridge host: sv5.mcsv.me
Minecraft bridge port: 27220
Bridge secret: <copy from plugins/vc_mumble/config.toml>
```

Typical local Mumble settings:

```text
Server name: Minecraft Voice
Mumble port: 64738
Max users: 20
Voice range: 30
```

The phone initiates the TCP connection to Endstone, so the phone does not need an incoming port for the Minecraft state bridge.

## 4. Pair players

A Minecraft player's Mumble username is explicit; it does not need to equal the Minecraft name.

Run as the player:

```text
/vcmumble pair MyMumbleName
```

Set an optional personal range:

```text
/vcmumble range 30
```

Remove pairing:

```text
/vcmumble unpair
```

## 5. Mumble client connection

When the embedded Mumble core validation gate is complete, stock clients connect to the Android device on the app's configured Mumble port. Mumble needs both TCP and UDP reachability on that port.

LAN example:

```text
Address: <Android phone LAN IP>
Port: 64738
Username: MyMumbleName
```

Public mobile-data hosting is a later phase because carrier CGNAT commonly prevents direct inbound access to the phone. The Endstone bridge itself is unaffected because the phone connects outbound to Minecraft.

## Security notes

- Do not publish `plugins/vc_mumble/config.toml`.
- The bridge secret is stored in the Android app via Android Keystore backed encryption.
- Protocol v1 authenticates with HMAC-SHA256 challenge/response.
- Player position data is currently plain TCP after authentication; TLS is planned before treating the direct bridge as hardened for untrusted networks.
