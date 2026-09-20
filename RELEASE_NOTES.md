# VC Mumble Server v0.6.0-beta.3

This beta focuses on everyday usability and Android background reliability while keeping the embedded Mumble server architecture stable.

## Highlights

- Modern Light/Dark interface with simpler wording and cleaner controls
- Sleepy Shop launcher icon
- Battery optimization prompt with Open / Later choices
- Improved background reliability with foreground service, CPU wake protection, persisted desired server state, and periodic TCP health checks
- Server remains intended to stay active when the app is sent to the background or removed from Recent Apps
- Minecraft Proximity controls moved to the Home screen
- Minecraft Proximity routing is always enabled on the mobile side
- Bridge Secret can be securely generated and copied in the app
- PortWarp remains an external companion app and is not embedded in the APK

## What works

- Embedded Mumble 1.6.870 server on Android ARM64
- Qt 6.8.3 Android runtime
- OpenSSL 3.6.3 TLS backend
- TCP + UDP listener on the configured Mumble port
- Real TCP readiness checks before ONLINE is shown
- Persistent in-app diagnostics and exported log.txt
- Clean Stop -> Start lifecycle using a dedicated Android :mumble process
- Background runtime protection and health monitoring
- Normal stock Mumble/Mumla protocol path
- PortWarp companion-app guidance for public TCP+UDP access

## Public access

VC Mumble Server runs the Mumble server only. For public access, install the official PortWarp Android app and create a TCP+UDP tunnel to:

```text
127.0.0.1:<your Mumble port>
```

The default Mumble port is:

```text
64738
```

Keep VC Mumble Server and PortWarp running while the public tunnel is in use.

## Background operation

When the app is not exempt from Android battery optimization, it prompts the user to allow unrestricted battery use. The Mumble service uses foreground-service runtime protection and a partial CPU wake lock while the server is running.

On devices with aggressive battery management, allowing unrestricted battery use is recommended for reliable background hosting.

## Minecraft proximity

The mobile app now keeps Minecraft Proximity routing enabled and exposes the proximity settings on the Home screen.

The Endstone bridge/plugin remains a separate integration step. The current release prepares the mobile side but does not claim complete end-to-end Minecraft proximity voice until the plugin side is finished and tested.

## Build provenance

The release APK is built by GitHub Actions from the main branch using pinned:

- Mumble 1.6.870
- Qt 6.8.3
- OpenSSL 3.6.3
- Android NDK 28.2.13676358
- vcpkg 2026.07.29

The release workflow rejects APKs that contain an embedded `libpwrp` runtime.

A SHA-256 checksum file is published beside the APK.

Release channel: GitHub Actions main build.
