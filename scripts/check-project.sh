#!/usr/bin/env sh
set -eu
for f in \
  settings.gradle.kts \
  build.gradle.kts \
  app/build.gradle.kts \
  app/src/main/AndroidManifest.xml \
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
grep -Fq 'Qt Android native entrypoint export: main OK' scripts/build-mumble-android-core.sh
grep -Fq "[[:space:]]+maingrep -Fq 'libvcserver does not export dynamic symbol main' scripts/build-mumble-android-core.sh
grep -Fq 'CORE_DYNAMIC="$("${LLVM_READELF}" -d "${CORE_SO}")"' scripts/build-mumble-android-core.sh
grep -Fq 'Starting Mumble runtime' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
test "$(grep -Fc 'is_android_system_or_qt_lib()' scripts/build-mumble-android-core.sh)" -eq 1
test "$(grep -Fc 'find_external_candidate()' scripts/build-mumble-android-core.sh)" -eq 1
test "$(grep -Fc 'stage_external_lib()' scripts/build-mumble-android-core.sh)" -eq 1
test "$(grep -Fc 'Build the APK with:' scripts/build-mumble-android-core.sh)" -eq 1
echo "VC Mumble Server project structure: OK"
; then" scripts/build-mumble-android-core.sh
grep -Fq 'libvcserver does not export dynamic symbol main' scripts/build-mumble-android-core.sh
grep -Fq 'CORE_DYNAMIC="$("${LLVM_READELF}" -d "${CORE_SO}")"' scripts/build-mumble-android-core.sh
grep -Fq 'Starting Mumble runtime' app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java
echo "VC Mumble Server project structure: OK"
