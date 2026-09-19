# Embedded Mumble core build

VC Mumble Server keeps its Android UI/service independent from the upstream Mumble source tree. The pinned Mumble source is downloaded at build time, verified, minimally patched, cross-compiled, then packaged by Qt as an **Android Archive (AAR)** consumed by the Android app.

## Pinned upstream

- Mumble: **1.5.915**
- Source: `https://dl.mumble.info/stable/mumble-1.5.915.tar.gz`
- SHA-256: `2cb3f0c7aa60e2f08fed1d568da7d1f5115e3658a079b6d1a5468c5d6e2081d5`

Do not silently move this pin. A Mumble update is a dedicated change that must rerun stock-client and proximity tests.

## Prepare upstream

```bash
./scripts/fetch-mumble.sh
```

The preparation step keeps upstream Mumble as the top-level CMake project and applies a narrow Android patch:

1. on Android only, `mumble-server` becomes a `qt_add_executable(... MANUAL_FINALIZATION ...)` target;
2. Android compatibility guards are added around Linux-only code;
3. upstream `main()` is retained so QtService can start it through Qt's Android runtime;
4. process-wide standalone cleanup is made embedded-safe so Stop does not terminate the Android app process;
5. the default INI path is redirected to app-private storage on Android;
6. the native proximity table/JNI sources are compiled into the server target;
7. ordinary speech receiver additions in `Server::processMsg()` are gated through `VCProximity::shouldRoute()`;
8. the Android output name is `vcserver`;
9. Qt finalizes the target so Android deployment/AAR targets are generated.

The patcher intentionally aborts when the expected upstream source shape changes.

## Android Gradle modes

Normal development build:

```bash
gradle :app:assembleDebug
```

This uses the lightweight TCP+UDP smoke-test implementation. It validates the Android service/network lifecycle only and is not Mumble protocol compatible.

Embedded core build:

```bash
./scripts/build-mumble-android-core.sh
gradle -PvcMumbleCore=true :app:assembleDebug
```

The core build uses `MumbleServerService extends QtService`. Gradle consumes:

```text
.build/android-stage/vc-mumble-runtime.aar
```

rather than manually guessing/copying Qt runtime libraries into the app.

## Qt AAR pipeline

The native build script performs two deployment passes:

1. configure/build the prepared Mumble server for `arm64-v8a`;
2. inspect `libvcserver.so` with the Android NDK `llvm-readelf`;
3. resolve the non-Qt `DT_NEEDED` closure from the pinned Android native dependency prefix/NDK;
4. feed those external libraries back to the Qt target through `QT_ANDROID_EXTRA_LIBS`;
5. build Qt's official `mumble-server_make_aar` target;
6. copy the generated AAR to `.build/android-stage/vc-mumble-runtime.aar`;
7. fail immediately if the AAR does not contain the VC server native library, Qt Core/Network/SQL, the SQLite driver, or `QtService`.

Qt itself owns Qt runtime/plugin deployment. The project does not manually stage Qt shared libraries anymore.

## Runtime lifecycle

`MumbleServerService` writes server configuration into app-private storage:

```text
files/mumble/mumble-server.ini
files/mumble/mumble-server.sqlite
```

The server password is written to the private INI rather than exposed on the process command line.

QtService loads the AAR-packaged Qt runtime and the native library named by:

```xml
<meta-data android:name="android.app.lib_name" android:value="vcserver" />
```

Qt then invokes the Mumble server's ordinary native `main()` on its Qt thread. The Android patch changes final cleanup so a user Stop can unwind the embedded server without executing the standalone daemon's process-wide `exit()` path.

## Build environment

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
export QT_ANDROID_PREFIX=/path/to/Qt/android_arm64_v8a
export QT_HOST_PATH=/path/to/Qt/gcc_64
export VC_ANDROID_DEP_PREFIX=/path/to/android/native/dependencies
export PROTOC=/path/to/host/protoc

./scripts/build-mumble-android-core.sh
gradle -PvcMumbleCore=true :app:assembleDebug
```

Mumble stays the **top-level** CMake project because upstream build logic relies on its own source/build root.

## Required protocol gate

Do not call the APK Mumble-compatible until all of these pass on a physical Android ARM64 device:

1. stock Mumble/Mumla establishes the TCP control session;
2. authentication succeeds;
3. client joins Root;
4. two stock clients exchange Opus voice through the normal Mumble UDP path;
5. foreground service remains alive after background/screen-off;
6. Stop releases TCP/UDP/database resources;
7. a second Start succeeds without stale global state.

The proximity hook is already prepared in source, but production proximity validation starts only after this base protocol gate passes.
