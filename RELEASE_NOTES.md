# VC Mumble Server v0.5.0-beta.1

First public beta milestone with a real embedded Mumble server running directly on Android ARM64.

## What works

- Embedded Mumble 1.6.870 server
- Qt 6.8.3 Android runtime
- OpenSSL 3.6.3 TLS backend
- TCP + UDP listener on the configured Mumble port
- Real TCP readiness checks before ONLINE is shown
- Persistent in-app diagnostics and exported log.txt
- Clean Stop -> Start lifecycle using a dedicated Android :mumble process
- Normal stock Mumble/Mumla protocol path

## Important architecture fix

Qt/Murmur native state cannot be reliably restarted inside the same already-used Android process.

This release runs Mumble in:

```text
com.voicecraft.vcmumbleserver:mumble
```

Stopping the server shuts down Qt/Mumble and terminates only that isolated process. The next Start receives a completely fresh native runtime while the main app remains open.

## TLS

The APK packages:

- Qt OpenSSL TLS backend
- libcrypto_3.so
- libssl_3.so

with `ANDROID_OPENSSL_SUFFIX=_3`.

## Quick test

1. Install the APK on ARM64 Android.
2. Open VC Mumble Server.
3. Press QUICK START.
4. Wait for ONLINE.
5. Connect Mumla/Mumble to the displayed LAN IP on port 64738.
6. Stop the server.
7. Start it again and verify it returns ONLINE.

## Minecraft proximity

The Endstone bridge/proximity code is present in the repository, but the release gate for v0.5.0-beta.1 focuses on the standalone Android Mumble server. Full Minecraft proximity integration remains the next phase.

## Build provenance

The release APK is built by GitHub Actions from the main branch using pinned:

- Mumble 1.6.870
- Qt 6.8.3
- OpenSSL 3.6.3
- Android NDK 28.2.13676358
- vcpkg 2026.07.29

A SHA-256 checksum file is published beside the APK.
