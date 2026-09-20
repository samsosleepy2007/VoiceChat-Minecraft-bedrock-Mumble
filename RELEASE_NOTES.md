# VC Mumble Server v0.6.0-beta.1

This beta keeps the embedded Android Mumble server stable while simplifying public access: PortWarp now runs as its official Android companion app instead of being embedded inside VC Mumble Server.

## What works

- Embedded Mumble 1.6.870 server on Android ARM64
- Qt 6.8.3 Android runtime
- OpenSSL 3.6.3 TLS backend
- TCP + UDP listener on the configured Mumble port
- Real TCP readiness checks before ONLINE is shown
- Persistent in-app diagnostics and exported log.txt
- Clean Stop -> Start lifecycle using a dedicated Android :mumble process
- Normal stock Mumble/Mumla protocol path
- PortWarp companion-app guidance for public TCP+UDP access
- Direct Google Play button for the official PortWarp Android app

## Public access change

The experimental embedded PortWarp CLI/tunnel runtime has been removed from the APK.

VC Mumble Server now runs only the Mumble server. For public access, install the official PortWarp Android app and configure a TCP+UDP tunnel to:

```text
127.0.0.1:<your Mumble port>
```

The default Mumble port is:

```text
64738
```

Keep VC Mumble Server and PortWarp running while the public tunnel is in use.

## Why this changed

Embedding the Linux PortWarp CLI on Android required compatibility work around Android executable, DNS, TLS trust, and seccomp behavior. The official Android PortWarp app already handles the Android networking/runtime environment, so using it as a companion app is simpler and more reliable.

## Mumble process architecture

Mumble runs in the isolated Android process:

```text
com.voicecraft.vcmumbleserver:mumble
```

Stopping the server shuts down Qt/Mumble and terminates only that process. The next Start gets a fresh native runtime while the main app remains open.

## Quick test

1. Install the APK on ARM64 Android.
2. Open VC Mumble Server.
3. Press QUICK START.
4. Wait for ONLINE.
5. Connect Mumla/Mumble over LAN to the displayed IP on port 64738.
6. For public access, install PortWarp from the in-app Google Play button.
7. Create a TCP+UDP tunnel in PortWarp to 127.0.0.1:64738.
8. Verify an external Mumble client can connect through the PortWarp public endpoint.

## Minecraft proximity

The Endstone bridge/proximity code remains in the repository. Full Minecraft proximity integration is still a later phase after the standalone mobile server and public tunnel workflow are stable.

## Build provenance

The release APK is built by GitHub Actions from the main branch using pinned:

- Mumble 1.6.870
- Qt 6.8.3
- OpenSSL 3.6.3
- Android NDK 28.2.13676358
- vcpkg 2026.07.29

The release workflow also rejects APKs that still contain an embedded `libpwrp` runtime.

A SHA-256 checksum file is published beside the APK.

Release channel: GitHub Actions main build.
