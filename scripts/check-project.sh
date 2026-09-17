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
  scripts/fetch-mumble.sh \
  scripts/build-mumble-android-core.sh \
  scripts/prepare-mumble-source.py
do
  test -f "$f" || { echo "Missing: $f" >&2; exit 1; }
done
python3 tests/test_prepare_mumble_source.py
python3 tests/test_vc_mumble_bridge_contract.py
echo "VC Mumble Server project structure: OK"
