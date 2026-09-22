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
  app/src/main/res/mipmap-xxxhdpi/ic_launcher_sleepy.png \
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
python3 tests/test_android_start_bridge_independence.py
python3 tests/test_endstone_plugin_contract.py
python3 tests/test_endstone_bridge_integration.py

python3 - <<'PY'
from pathlib import Path
import struct
import zlib

path = Path("app/src/main/res/mipmap-xxxhdpi/ic_launcher_sleepy.png")
data = path.read_bytes()
assert data.startswith(b"\x89PNG\r\n\x1a\n"), "launcher icon is not a PNG"

offset = 8
saw_iend = False
while offset < len(data):
    assert offset + 12 <= len(data), "launcher PNG is truncated"
    length = struct.unpack(">I", data[offset:offset + 4])[0]
    chunk_type = data[offset + 4:offset + 8]
    chunk_data_start = offset + 8
    chunk_data_end = chunk_data_start + length
    crc_end = chunk_data_end + 4
    assert crc_end <= len(data), "launcher PNG chunk is truncated"
    stored_crc = struct.unpack(">I", data[chunk_data_end:crc_end])[0]
    actual_crc = zlib.crc32(chunk_type)
    actual_crc = zlib.crc32(data[chunk_data_start:chunk_data_end], actual_crc) & 0xffffffff
    assert stored_crc == actual_crc, f"launcher PNG CRC mismatch in {chunk_type!r}"
    offset = crc_end
    if chunk_type == b"IEND":
        saw_iend = True
        break

assert saw_iend, "launcher PNG has no IEND"
assert offset == len(data), "launcher PNG has trailing/corrupt bytes"
print("Launcher PNG integrity: OK")
PY

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
grep -Fq '(Ljava/lang/String;Ljava/lang/String;DDDFZI)V' app/src/main/cpp/mumble_jni.cpp
grep -Fq 'if (!speaker.voiceEnabled) return finish(0.0F, "speaker-mic-off");' native/mumble_android/VCProximity.cpp
grep -Fq '[VC-PROX-ROUTE]' native/mumble_android/VCProximity.cpp
grep -Fq '[VC-PROX-STATE]' native/mumble_android/VCProximity.cpp
grep -Fq 'float attenuationFactor' native/mumble_android/VCProximity.cpp
grep -Fq 'normalizedDistance <= 0.20' native/mumble_android/VCProximity.cpp
grep -Fq 'attenuationForNormalizedDistance(double normalizedDistance, int level)' native/mumble_android/VCProximity.cpp
grep -Fq 'mid = 0.80F' native/mumble_android/VCProximity.cpp
grep -Fq 'mid = 0.25F' native/mumble_android/VCProximity.cpp
grep -Fq 'speaker.attenuationLevel' native/mumble_android/VCProximity.cpp
grep -Fq 'VC_PROXIMITY_REGULAR_ATTENUATION' scripts/prepare-mumble-source.py
grep -Fq 'VC_PROXIMITY_LINKED_ATTENUATION' scripts/prepare-mumble-source.py
grep -Fq 'data.optBoolean("voiceEnabled", true)' app/src/main/java/com/voicecraft/vcmumbleserver/VCMumbleBridgeClient.java
grep -Fq 'data.optInt("attenuationLevel", 2)' app/src/main/java/com/voicecraft/vcmumbleserver/VCMumbleBridgeClient.java
grep -Fq 'NativeServer.touchPlayerStates();' app/src/main/java/com/voicecraft/vcmumbleserver/VCMumbleBridgeClient.java
grep -Fq 'NativeServer.setProximityStaleTimeoutMs(45000L);' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'g_staleTimeoutMs{ 45000 }' native/mumble_android/VCProximity.cpp
grep -Fq 'private static native void setProximityStaleTimeoutMsNative(long timeoutMs);' app/src/core/java/com/voicecraft/vcmumbleserver/NativeServer.java
grep -Fq 'private static native void updatePlayerStateNative(' app/src/core/java/com/voicecraft/vcmumbleserver/NativeServer.java
grep -Fq 'if (runtimeLoaded) setProximityStaleTimeoutMsNative(timeoutMs);' app/src/core/java/com/voicecraft/vcmumbleserver/NativeServer.java
grep -Fq 'host=0.0.0.0' app/src/main/java/com/voicecraft/vcmumbleserver/MumbleConfigWriter.java
grep -Fq 'new InetSocketAddress("127.0.0.1", port)' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'STARTUP_PROBE_MAX_ATTEMPTS = 30' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'Mumble core loaded but TCP port ' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq '"กำลังเริ่ม"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'public static final String FILE_NAME = "vc-mumble-server.log";' app/src/main/java/com/voicecraft/vcmumbleserver/ServerLog.java
grep -Fq 'ServerLog.file(context).getAbsolutePath()' app/src/main/java/com/voicecraft/vcmumbleserver/MumbleConfigWriter.java
grep -Fq 'VC_ANDROID_FOREGROUND_LOGFILE' scripts/prepare-mumble-source.py
grep -Fq 'Button copyLog = secondaryButton("คัดลอก");' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'Button downloadLog = secondaryButton("บันทึกไฟล์");' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'Button clearLog = dangerButton("ล้าง");' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
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
grep -Fq '"เปิด PortWarp"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"com.ribeirosoftware.portwarp"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'embedded PortWarp runtime must not be packaged' .github/workflows/android-mumble-core.yml
grep -Fq '"market://details?id=" + packageName' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'private void openUrl(String url, String label)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"ปลายทาง: 127.0.0.1:" + ServerConfig.load(this).port' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
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
grep -Fq '"บันทึกการทำงานของเซิร์ฟเวอร์"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '@color/cyber_bg' app/src/main/res/values/styles.xml
grep -Fq 'Theme.Material.NoActionBar' app/src/main/res/values-night/styles.xml
grep -Fq '<color name="cyber_bg">#0B1120</color>' app/src/main/res/values-night/colors.xml
grep -Fq '<color name="cyber_neon">#2563EB</color>' app/src/main/res/values-night/colors.xml
grep -Fq '<color name="cyber_bg">#F6F7F9</color>' app/src/main/res/values/colors.xml
grep -Fq 'private ScrollView logScroll;' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'dp(360)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'logScroll.fullScroll(View.FOCUS_DOWN)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'requestDisallowInterceptTouchEvent(true)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'MotionEvent.ACTION_DOWN' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '.scaleX(0.98f)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
if grep -Fq 'setShadowLayer' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java; then
  echo "ERROR: modern UI must not restore neon shadow effects" >&2
  exit 1
fi
grep -Fq 'private View pageView(int page)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '.translationX(-direction * dp(18))' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'ObjectAnimator.ofFloat(status, View.ALPHA' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'statusPulse.setRepeatCount(ValueAnimator.INFINITE)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'android.permission.WAKE_LOCK' app/src/main/AndroidManifest.xml
grep -Fq 'android:stopWithTask="false"' app/src/main/AndroidManifest.xml
grep -Fq 'android:icon="@mipmap/ic_launcher_sleepy"' app/src/main/AndroidManifest.xml
grep -Fq 'android:roundIcon="@mipmap/ic_launcher_sleepy"' app/src/main/AndroidManifest.xml
grep -Fq 'versionCode = 12' app/build.gradle.kts
grep -Fq 'versionName = "0.6.0-beta.7"' app/build.gradle.kts
test ! -e app/src/main/res/drawable-nodpi/ic_launcher.png
test ! -e app/src/main/res/drawable-nodpi/ic_launcher_sleepy.png
grep -Fq 'PowerManager.PARTIAL_WAKE_LOCK' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'WifiManager.WIFI_MODE_FULL_HIGH_PERF' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'Build.VERSION.SDK_INT <= 33' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'BACKGROUND_HEALTH_INTERVAL_MS = 60_000L' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq '"VCMumble-Background-Health"' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'ServerRuntimeState.shouldRun' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
grep -Fq 'ServerRuntimeState.setShouldRun(this, true)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'ServerRuntimeState.setShouldRun(this, false)' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'showBatteryAccessPromptIfNeeded()' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"เปิดภายหลัง"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'Settings.ACTION_APPLICATION_DETAILS_SETTINGS' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' app/src/main/AndroidManifest.xml
grep -Fq 'isIgnoringBatteryOptimizations' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq 'buildProximityCard()' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"Minecraft Proximity"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"เปิดตลอด"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
grep -Fq '"สุ่มใหม่"' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java
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
if grep -Fq 'buildBackgroundCard' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java; then
  echo "ERROR: battery guidance must use a popup, not a persistent background card" >&2
  exit 1
fi
if grep -Fq 'VC MUMBLE // NODE' app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java; then
  echo "ERROR: cyberpunk UI labels must not return" >&2
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

rm -rf .build/mic-addon-contract
mkdir -p .build/mic-addon-contract
python3 scripts/build-mic-addon-release.py --output .build/mic-addon-contract >/dev/null
test -f .build/mic-addon-contract/VC_Mumble_ItemMic_v2.7.6.mcaddon
unzip -t .build/mic-addon-contract/VC_Mumble_ItemMic_v2.7.6.mcaddon >/dev/null

echo "VC Mumble Server project structure: OK"
