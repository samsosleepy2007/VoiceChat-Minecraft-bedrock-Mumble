#!/usr/bin/env sh
set -eu

for f in \
  settings.gradle.kts \
  build.gradle.kts \
  app/build.gradle.kts \
  app/src/main/AndroidManifest.xml \
  app/src/main/res/values/colors.xml \
  app/src/main/res/values-night/colors.xml \
  app/src/main/res/values/styles.xml \
  app/src/main/res/values-night/styles.xml \
  app/src/main/cpp/CMakeLists.txt \
  app/src/main/cpp/transport_smoketest_jni.cpp \
  app/src/main/cpp/mumble_jni.cpp \
  app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java \
  app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java \
  app/src/core/java/org/qtproject/qt/android/RestartableQtService.java \
  app/src/smoke/java/com/voicecraft/vcmumbleserver/MumbleServerService.java \
  app/src/main/java/com/voicecraft/vcmumbleserver/MumbleConfigWriter.java \
  app/src/main/java/com/voicecraft/vcmumbleserver/VCMumbleBridgeClient.java \
  app/src/main/java/com/voicecraft/vcmumbleserver/SecretStore.java \
  app/src/main/java/com/voicecraft/vcmumbleserver/ServerConfig.java \
  app/src/main/java/com/voicecraft/vcmumbleserver/ServerLog.java \
  app/src/main/java/com/voicecraft/vcmumbleserver/ServerRuntimeState.java \
  endstone-plugin/pyproject.toml \
  endstone-plugin/src/endstone_vc_mumble/plugin.py \
  endstone-plugin/src/endstone_vc_mumble/bridge.py \
  endstone-plugin/src/endstone_vc_mumble/listener.py \
  native/mumble_android/VCProximity.h \
  native/mumble_android/VCProximity.cpp \
  native/mumble_android/AndroidEmbed.cpp \
  native/mumble_android/android-package/settings.gradle \
  scripts/fetch-mumble.sh \
  scripts/build-mumble-android-core.sh \
  scripts/prepare-mumble-source.py
do
  test -f "$f" || { echo "Missing: $f" >&2; exit 1; }
done

python3 tests/test_prepare_mumble_source.py
python3 tests/test_vc_mumble_bridge_contract.py

bash -n scripts/build-mumble-android-core.sh

grep -Fq 'libvcserver_${ANDROID_ABI}.so' scripts/build-mumble-android-core.sh
grep -Fq 'CORE_MACHINE=' scripts/build-mumble-android-core.sh
grep -Fq 'AArch64' scripts/build-mumble-android-core.sh
grep -Fq 'find -L "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt"' scripts/build-mumble-android-core.sh
grep -Fq 'Using Android ELF inspector:' scripts/build-mumble-android-core.sh
grep -Fq 'VC_ANDROID_PACKAGE_SOURCE_DIR' scripts/build-mumble-android-core.sh
grep -Fq 'QT_ANDROID_PACKAGE_SOURCE_DIR' scripts/prepare-mumble-source.py
grep -Fq 'rootProject.name = rootDir.name' native/mumble_android/android-package/settings.gradle
grep -Fq 'VC_ANDROID_MAIN_EXPORT' scripts/prepare-mumble-source.py
grep -Fq 'QT_ANDROID_NO_EXIT_CALL' scripts/prepare-mumble-source.py
grep -Fq -- '--dyn-syms' scripts/build-mumble-android-core.sh
grep -Fq 'CORE_DYNSYMS="$("${LLVM_READELF}" --dyn-syms "${CORE_SO}")"' scripts/build-mumble-android-core.sh
grep -Fq '<<<"${CORE_DYNSYMS}"' scripts/build-mumble-android-core.sh
grep -Fq 'Qt Android native entrypoint export: main OK' scripts/build-mumble-android-core.sh
grep -Fq 'libvcserver does not export dynamic symbol main' scripts/build-mumble-android-core.sh
grep -Fq 'CORE_DYNAMIC="$("${LLVM_READELF}" -d "${CORE_SO}")"' scripts/build-mumble-android-core.sh
grep -Fq 'Starting Mumble runtime' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'RegisterNatives' app/src/main/cpp/mumble_jni.cpp
grep -Fq 'FindClass("com/voicecraft/vcmumbleserver/NativeServer")' app/src/main/cpp/mumble_jni.cpp
grep -Fq '"setProximityStaleTimeoutMsNative"' app/src/main/cpp/mumble_jni.cpp
grep -Fq '"updatePlayerStateNative"' app/src/main/cpp/mumble_jni.cpp
grep -Fq 'private static native void setProximityStaleTimeoutMsNative(long timeoutMs);' app/src/core/java/com/voicecraft/vcmumbleserver/NativeServer.java
grep -Fq 'private static native void updatePlayerStateNative(' app/src/core/java/com/voicecraft/vcmumbleserver/NativeServer.java
grep -Fq 'if (runtimeLoaded) setProximityStaleTimeoutMsNative(timeoutMs);' app/src/core/java/com/voicecraft/vcmumbleserver/NativeServer.java
grep -Fq 'host=0.0.0.0' app/src/main/java/com/voicecraft/vcmumbleserver/MumbleConfigWriter.java
grep -Fq 'new InetSocketAddress("127.0.0.1", port)' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'STARTUP_PROBE_MAX_ATTEMPTS = 30' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'Mumble core loaded but TCP port ' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq '"● กำลังเริ่ม"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'public static final String FILE_NAME = "vc-mumble-server.log";' app/src/main/java/com/voicecraft/vcmumbleserver/ServerLog.java
grep -Fq 'ServerLog.file(context).getAbsolutePath()' app/src/main/java/com/voicecraft/vcmumbleserver/MumbleConfigWriter.java
grep -Fq 'VC_ANDROID_FOREGROUND_LOGFILE' scripts/prepare-mumble-source.py
grep -Fq '"คัดลอก Log"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"บันทึก log.txt"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"ล้าง Log"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'Intent.ACTION_CREATE_DOCUMENT' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'Intent.EXTRA_TITLE, "log.txt"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'QtServiceLoader.loadQtLibraries' app/src/core/java/org/qtproject/qt/android/RestartableQtService.java
grep -Fq 'System.load(candidate.getAbsolutePath())' app/src/core/java/org/qtproject/qt/android/RestartableQtService.java
grep -Fq '"QT-LIB"' app/src/core/java/org/qtproject/qt/android/RestartableQtService.java
grep -Fq '"QT-ERROR"' app/src/core/java/org/qtproject/qt/android/RestartableQtService.java
grep -Fq 'qtStartupError()' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'Qt startup failed: ' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'useLegacyPackaging = true' app/build.gradle.kts
grep -Fq '"JNI"' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'Scheduling TCP readiness probe' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'VC_ANDROID_BOOTSTRAP_LOG' scripts/prepare-mumble-source.py
grep -Fq 'VC_ANDROID_NO_UNIX_DAEMON' scripts/prepare-mumble-source.py
grep -Fq 'VCPKG_LIBRARY_LINKAGE dynamic' cmake/triplets/arm64-android-dynamic.cmake
grep -Fq 'openssl:arm64-android-dynamic' .github/workflows/android-mumble-core.yml
grep -Fq 'VC_ANDROID_TLS_PREFIX' .github/workflows/android-mumble-core.yml
grep -Fq 'libcrypto_3.so' scripts/build-mumble-android-core.sh
grep -Fq 'libssl_3.so' scripts/build-mumble-android-core.sh
grep -Fq 'patchelf --set-soname libcrypto_3.so' scripts/build-mumble-android-core.sh
grep -Fq 'ANDROID_OPENSSL_SUFFIX' scripts/prepare-mumble-source.py
grep -Fq 'probeSystemLoad(nativeDir, "crypto_3", "openssl")' app/src/core/java/org/qtproject/qt/android/RestartableQtService.java
grep -Fq 'probeSystemLoad(nativeDir, "ssl_3", "openssl")' app/src/core/java/org/qtproject/qt/android/RestartableQtService.java
grep -Fq 'android:process=":mumble"' app/src/main/AndroidManifest.xml
grep -Fq 'terminateProcessOnDestroy' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'android.os.Process.killProcess(pid)' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'Startup failed; stopping isolated Mumble process' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'refreshServerStateFromTcp()' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"เปิด PortWarp ใน Google Play"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"com.ribeirosoftware.portwarp"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'embedded PortWarp runtime must not be packaged' .github/workflows/android-mumble-core.yml
grep -Fq '"market://details?id=" + packageName' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'private void openUrl(String url, String label)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"เป้าหมาย PortWarp: 127.0.0.1:" + ServerConfig.load(this).port' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
if grep -Fq 'PortWarpTunnelService' app/src/main/AndroidManifest.xml; then
  echo "ERROR: embedded PortWarp service must not be registered" >&2
  exit 1
fi
test ! -e app/src/main/java/com/voicecraft/vcmumbleserver/PortWarpTunnelService.java
test ! -e app/src/main/java/com/voicecraft/vcmumbleserver/PortWarpExecProbe.java
test ! -e scripts/fetch-portwarp-android-runtime.sh
test ! -e native/portwarp_dns_launcher.c
grep -Fq 'new InetSocketAddress("127.0.0.1", probePort)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'PAGE_HOME = 0' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'PAGE_LOG = 1' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'PAGE_SETTINGS = 2' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"หน้าหลัก"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"ตั้งค่า"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"LOG // TERMINAL"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '@color/cyber_bg' app/src/main/res/values/styles.xml
grep -Fq 'Theme.Material.NoActionBar' app/src/main/res/values-night/styles.xml
grep -Fq '<color name="cyber_neon">#00E5FF</color>' app/src/main/res/values-night/colors.xml
grep -Fq 'private ScrollView logScroll;' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'dp(360)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'logScroll.fullScroll(View.FOCUS_DOWN)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'requestDisallowInterceptTouchEvent(true)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'MotionEvent.ACTION_DOWN' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '.scaleX(0.965f)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'setShadowLayer(dp(6)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'private View pageView(int page)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '.translationX(-direction * dp(18))' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'ObjectAnimator.ofFloat(status, View.ALPHA' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'statusPulse.setRepeatCount(ValueAnimator.INFINITE)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'android.permission.WAKE_LOCK' app/src/main/AndroidManifest.xml
grep -Fq 'android:stopWithTask="false"' app/src/main/AndroidManifest.xml
grep -Fq 'PowerManager.PARTIAL_WAKE_LOCK' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'WifiManager.WIFI_MODE_FULL_HIGH_PERF' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'Build.VERSION.SDK_INT <= 33' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'BACKGROUND_HEALTH_INTERVAL_MS = 60_000L' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq '"VCMumble-Background-Health"' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'ServerRuntimeState.shouldRun' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'ServerRuntimeState.setShouldRun(this, true)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'ServerRuntimeState.setShouldRun(this, false)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'MumbleServerService.EXTRA_BACKGROUND' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"BACKGROUND RUNTIME // PROTECTION"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'Settings.ACTION_APPLICATION_DETAILS_SETTINGS' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'isIgnoringBatteryOptimizations' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'buildProximityCard()' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"MINECRAFT PROXIMITY // ALWAYS ON"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"● เปิดใช้งานตลอดเวลา"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"สุ่ม Secret"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"คัดลอก"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'new SecureRandom().nextBytes(bytes)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'boolean proximity = true;' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'this.proximityEnabled = true;' app/src/main/java/com/voicecraft/vcmumbleserver/ServerConfig.java
grep -Fq '.putBoolean("proximity_enabled", true)' app/src/main/java/com/voicecraft/vcmumbleserver/ServerConfig.java
grep -Fq 'boolean proximityActive = true;' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
if grep -Fq 'CheckBox proximityEnabled' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java; then
  echo "ERROR: proximity enable/disable UI must not return" >&2
  exit 1
fi
grep -Fq '"PROBE",' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java || { echo "Missing contract: TCP probe log tag" >&2; exit 1; }
grep -Fq '"SERVICE",' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java || { echo "Missing contract: service log tag" >&2; exit 1; }
grep -Fq '"ERROR",' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java || { echo "Missing contract: error log tag" >&2; exit 1; }
if grep -Fq 'Java_com_voicecraft_vcmumbleserver_NativeServer_setProximityEnabledNative' app/src/main/cpp/mumble_jni.cpp; then
  echo "ERROR: real core JNI must use RegisterNatives, not legacy Java_com_* discovery" >&2
  exit 1
fi

test "$(grep -Fc 'is_android_system_or_qt_lib()' scripts/build-mumble-android-core.sh)" -eq 1
test "$(grep -Fc 'find_external_candidate()' scripts/build-mumble-android-core.sh)" -eq 1
test "$(grep -Fc 'stage_external_lib()' scripts/build-mumble-android-core.sh)" -eq 1
test "$(grep -Fc 'Build the APK with:' scripts/build-mumble-android-core.sh)" -eq 1
test "$(grep -Fc 'CORE_DYNSYMS=' scripts/build-mumble-android-core.sh)" -eq 1

echo "VC Mumble Server project structure: OK"
