# VC Mumble Server

Android-hosted Mumble server for Minecraft Bedrock voice chat.

> Release line: **v0.6.0-beta.7**  
> Embedded server: **Mumble 1.6.870**  
> Qt: **6.8.3**  
> OpenSSL: **3.6.3**  
> Target: **Android ARM64-v8a**

VC Mumble Server runs a real Mumble/Murmur server directly on an Android phone. Stock Mumble-compatible clients such as Mumla connect normally over TCP/UDP. The Minecraft/Endstone bridge is a separate layer that supplies player identity and position data for proximity routing.

## Current status

The Android Mumble core has passed the physical-device startup gate:

- Qt native runtime loads successfully on Android
- OpenSSL TLS backend loads successfully
- Mumble database initializes
- the default virtual server boots
- Mumble binds on `0.0.0.0:64738`
- the app verifies the listener with a localhost TCP readiness probe
- Stop -> Start works repeatedly by restarting the isolated Mumble process cleanly
- stock Mumble/Mumla clients can use the normal Mumble protocol path

The Minecraft/Endstone proximity bridge now supports the /vcb control panel, per-player attenuation levels, heartbeat-backed state freshness, and Mic ON/OFF synchronization. Endstone plugin v0.4.1 feeds the native proximity routing layer, while VC Mumla v0.4 Stable Gain applies per-listener distance volume.

## How the system works

### 1. Android UI process

The normal app UI runs in the main Android process:

```text
com.voicecraft.vcmumbleserver
```

It is responsible for:

- server name, port, password and max-user settings
- writing `mumble-server.ini`
- showing ONLINE / STARTING / OFFLINE state
- showing and exporting server logs
- starting and stopping the Mumble service

The UI does **not** host Qt or Murmur directly.

### 2. Dedicated Mumble process

The embedded Mumble server runs in a separate Android process:

```text
com.voicecraft.vcmumbleserver:mumble
```

This separation is important. Qt/Murmur keeps native process-wide state and cannot be safely restarted inside the same already-used process. When the user presses Stop, the app:

1. asks the native Mumble server to stop
2. shuts down Qt
3. stops the foreground service
4. terminates only the isolated `:mumble` process

The main app remains open. The next Start launches a completely fresh Mumble/Qt process, avoiding stale `AlreadyLoaded` state and failed restarts.

### 3. Qt and native library loading

The APK contains the ARM64 Qt runtime and Mumble native library.

Important packaged libraries include:

```text
libQt6Core_arm64-v8a.so
libQt6Gui_arm64-v8a.so
libQt6Network_arm64-v8a.so
libQt6Sql_arm64-v8a.so
libplugins_platforms_qtforandroid_arm64-v8a.so
libplugins_tls_qopensslbackend_arm64-v8a.so
libcrypto_3.so
libssl_3.so
libvcserver_arm64-v8a.so
```

Android is configured with legacy JNI packaging so these libraries are extracted to `ApplicationInfo.nativeLibraryDir`, which is required by the Qt Android service loader used by this project.

The app preflights the native libraries and records exact `System.load()` failures in the app log before Murmur starts.

### 4. TLS / OpenSSL

Mumble requires TLS for its control connection.

Qt's Android OpenSSL backend is packaged together with shared OpenSSL 3.6.3 libraries:

```text
libcrypto_3.so
libssl_3.so
```

The process sets:

```text
ANDROID_OPENSSL_SUFFIX=_3
```

before Mumble initializes SSL, allowing Qt's `qopensslbackend` plugin to resolve the bundled OpenSSL runtime.

### 5. Mumble startup

The native Mumble entrypoint is exported from `libvcserver_arm64-v8a.so` and launched by Qt's Android service loader.

Startup flow:

```text
Start button
    |
    v
Write mumble-server.ini
    |
    v
Start foreground service in :mumble process
    |
    v
Load Qt + OpenSSL + libvcserver
    |
    v
MumbleSSL initialization
    |
    v
Read config
    |
    v
Open / create SQLite database
    |
    v
Boot default virtual server
    |
    v
Bind TCP + UDP on configured port
    |
    v
App TCP readiness probe
    |
    v
ONLINE
```

The default listener is:

```text
0.0.0.0:64738
```

so other devices on the same LAN can connect using the phone's LAN IPv4 address.

### 6. Readiness checking

The app does not mark the server ONLINE merely because Qt is alive.

It repeatedly probes:

```text
127.0.0.1:<configured port>
```

and only reports ONLINE after the TCP listener actually accepts a connection.

If the listener does not open within the startup window, the isolated Mumble process is shut down so the next retry starts cleanly.

### 7. Logging

Android and native Mumble write into the same app-private log:

```text
/data/user/0/com.voicecraft.vcmumbleserver/files/vc-mumble-server.log
```

The UI provides:

- Copy Log
- Download `log.txt`
- Clear Log

Useful log components include:

```text
[UI]
[SERVICE]
[QT]
[QT-RES]
[QT-LIB]
[QT-ERROR]
[JNI]
[PROBE]
[NATIVE-BOOT]
[BRIDGE]
```

## Default topology

```text
                     +---------------------------+
                     |   Android phone / tablet  |
                     |                           |
Mumble / Mumla <---->| TCP + UDP :64738         |
clients              |                           |
                     | VC Mumble Server APK      |
                     | Qt 6.8.3 + Mumble 1.6.870|
                     +-------------+-------------+
                                   |
                                   | optional authenticated
                                   | Minecraft bridge
                                   v
                     +---------------------------+
                     | Endstone Minecraft server |
                     | VC Mumble Endstone plugin |
                     | TCP :27220                |
                     +---------------------------+
```

## Defaults

| Setting | Default |
| --- | --- |
| Server name | Minecraft Voice |
| Mumble port | 64738 TCP + UDP |
| Max users | 20 |
| Proximity range | 30 blocks |
| Endstone bridge port | 27220 TCP |
| Proximity routing | Always enabled on the mobile side |

## Quick start

1. Install the APK on an ARM64 Android device.
2. Open **VC Mumble Server**.
3. Press **QUICK START** or configure the server manually.
4. Wait until the app shows **ONLINE**.
5. From the same phone, connect a Mumble client to `127.0.0.1:64738`.
6. From another device on the same Wi-Fi/LAN, connect to the Android phone's displayed LAN IP and port `64738`.
7. If prompted about the self-signed server certificate during testing, review and accept it in the client.

## Stop / restart behavior

A normal Stop intentionally destroys the isolated Mumble process.

Expected lifecycle:

```text
START -> ONLINE -> STOP -> :mumble process exits
                      |
                      v
             next START creates
             a new clean process
```

This is by design and fixes the Qt/Murmur same-process restart problem.

## Endstone proximity bridge

The standalone Endstone plugin lives in:

```text
endstone-plugin/
```

The planned/implemented bridge topology is:

```text
Minecraft Bedrock player state
        |
        v
VC Mumble Endstone plugin
        |
        | TCP + authenticated NDJSON
        | HMAC-SHA256 challenge/response
        v
VC Mumble Server APK
        |
        v
Native Mumble proximity routing
```

Commands:

```text
/vcb
```

The bridge tracks Minecraft identity, dimension, XYZ position, Mic ON/OFF state, voice range, and attenuation level and maps those players to Mumble usernames. All Mumble users may stay in the Root channel; proximity routing and smooth distance-based attenuation are handled by the server together with VC Mumla v0.4 Stable Gain.

### In-app Endstone plugin and Minecraft Addon downloads

VC Mumble Server can prepare the Endstone plugin and download the matching Item Mic Addon directly from this repository's GitHub Releases.

When the user presses **Download Plugin (.whl)**, the app:

1. requests the current Releases list from GitHub instead of using a hard-coded tag;
2. ignores drafts, includes stable and prerelease releases, and selects the most recently published release that contains an `endstone_vc_mumble-*.whl` asset;
3. downloads that wheel together with `SHA256SUMS.txt` from the same release;
4. verifies the original wheel SHA-256 before modifying it;
5. writes the Android device's encrypted-at-rest Bridge Secret into `endstone_vc_mumble/config.toml`;
6. rebuilds the wheel `.dist-info/RECORD` hashes and sizes; and
7. saves the configured wheel under its original valid wheel filename.

**Download Addon (.mcaddon)** performs a separate fresh release lookup and selects the newest published release containing a `VC_Mumble_ItemMic_*.mcaddon` asset. The original Addon is downloaded unchanged after its SHA-256 is verified against `SHA256SUMS.txt` from the same release.

Both download actions resolve GitHub Releases again every time they are pressed, so newer beta or stable assets can be picked up without shipping a new Android APK just to change a download URL. A separate **Download config.toml** action is available for servers where the plugin is already installed and its existing data-folder config must be replaced manually.

The configured wheel and exported config contain the Bridge Secret in plaintext by necessity. Treat those exported files as private server credentials. The Addon download does not contain the Bridge Secret.

### Endstone compatibility notice

On the first app launch, VC Mumble Server shows a red compatibility notice explaining that Minecraft proximity integration requires a **Minecraft Bedrock server running Endstone**. The notice includes the Endstone artwork supplied for the app and a short MCSV setup guide. Once acknowledged, it is not shown automatically again; it can be reopened from **Settings → Endstone / MCSV guide**.

The app uses validated embedded WebP artwork for both the MCSV logo and Endstone notice instead of relying on a binary drawable upload. Project contract checks verify the expected dimensions and SHA-256 of both embedded assets before Android builds.

### Optional MCSV one-click install

For MCSV-hosted Bedrock/Endstone servers, the Android app also has an optional green **MCSV** card. The user only pastes an MCSV API key and presses **Install via MCSV**.

The app validates the key, confirms the bound server is Minecraft Bedrock + Endstone, selects an allocated non-game port for the VC Mumble bridge, then installs the complete VC Mumble server-side setup in one action:

1. resolve + SHA-verify the newest Endstone wheel;
2. inject the Android Bridge Secret, upload the wheel to `/plugins`, and write `/plugins/vc_mumble/config.toml`;
3. resolve + SHA-verify the newest `VC_Mumble_ItemMic_*.mcaddon`;
4. stage/decompress the Addon through MCSV file tools, detect its Behavior/Resource manifests, install the packs to `/behavior_packs` and `/resource_packs`, and merge the pack UUID/version into the active world's `world_behavior_packs.json` and `world_resource_packs.json` without removing unrelated packs;
5. restart the MCSV server once and fill the Android Bridge host/port automatically.

The MCSV API key is used only for that install request and is not persisted by VC Mumble Server. For a custom-permission key, enable only: `server_info`, `domain_info`, `files_list`, `files_read`, `files_upload_base64`, `files_decompress`, `files_rename`, `files_delete`, `files_write`, and `power_action`.

## Building the Android Mumble core

Requirements used by CI:

- JDK 17
- Android SDK 36
- Android NDK 28.2.13676358
- Gradle 9.6.0
- Qt 6.8.3 Android ARM64
- vcpkg 2026.07.29
- OpenSSL 3.6.3

Build:

```bash
./scripts/fetch-mumble.sh
./scripts/build-mumble-android-core.sh
gradle -PvcMumbleCore=true :app:assembleDebug
```

The generated embedded runtime AAR is staged at:

```text
.build/android-stage/vc-mumble-runtime.aar
```

The APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Source pinning

The Android core build currently pins:

- Mumble **v1.6.870**
- Qt **6.8.3**
- OpenSSL **3.6.3**
- NDK **28.2.13676358**
- vcpkg **2026.07.29**

The Mumble source tarball is checksum-validated before use.

## Release validation

For the standalone Android server milestone, a release should pass:

```text
1. Install APK on ARM64 Android
2. Start server
3. Qt/OpenSSL initialization succeeds
4. Mumble database initializes
5. Server listens on configured TCP/UDP port
6. App reports ONLINE only after TCP readiness succeeds
7. Stock Mumble/Mumla client connects
8. Stop server
9. Start again
10. Listener opens again without Qt AlreadyLoaded/main() restart failure
```

Minecraft proximity routing has its own later validation gate involving Endstone pairing, range checks and dimension isolation.

## Project layout

```text
app/                    Android application
app/src/core/           Embedded Qt/Mumble service implementation
app/src/smoke/          Lightweight transport-only development runtime
native/mumble_android/  Android/Mumble patches and native proximity code
endstone-plugin/        Standalone Endstone integration
scripts/                Source preparation, build and validation scripts
docs/                   Additional design and setup documentation
```

## Documentation

- [Setup](docs/SETUP.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Endstone integration](docs/VC_MUMBLE_ENDSTONE.md)
- [Android Mumble port](docs/MUMBLE_ANDROID_PORT.md)
- [Mumble core build](docs/MUMBLE_CORE_BUILD.md)
- [Roadmap](docs/ROADMAP.md)

## License / upstream projects

This project embeds and adapts upstream Mumble and Qt components. Check the repository and upstream dependency licenses before redistribution.

Mumble is an upstream project of the Mumble developers. Qt is provided by The Qt Company/community. OpenSSL is provided by the OpenSSL Project.
