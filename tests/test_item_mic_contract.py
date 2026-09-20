# Android beta.5 checkpoint: Item Mic mute state must reach native routing.\nfrom pathlib import Path

root = Path(__file__).resolve().parents[1]
plugin = (root / "endstone-plugin/src/endstone_vc_mumble/plugin.py").read_text(encoding="utf-8")
model = (root / "endstone-plugin/src/endstone_vc_mumble/model.py").read_text(encoding="utf-8")
client = (root / "app/src/main/java/com/voicecraft/vcmumbleserver/VCMumbleBridgeClient.java").read_text(encoding="utf-8")
native_java = (root / "app/src/core/java/com/voicecraft/vcmumbleserver/NativeServer.java").read_text(encoding="utf-8")
native_cpp = (root / "native/mumble_android/VCProximity.cpp").read_text(encoding="utf-8")
jni = (root / "app/src/main/cpp/mumble_jni.cpp").read_text(encoding="utf-8")

for expected in [
    'MIC_ON_TAG = "vcmumble.mic.on"',
    'MIC_OFF_TAG = "vcmumble.mic.off"',
    'RANGE_REQUEST_PREFIX = "vcmumble.vr.request."',
    'PAIR_REQUEST_PREFIX = "vcmumble.pair.request."',
    'PAIR_UNPAIR_PREFIX = "vcmumble.pair.unpair."',
    'player.scoreboard_tags',
    'add_scoreboard_tag',
    'remove_scoreboard_tag',
    '"micEnabled": bool(state.mic_enabled)',
]:
    assert expected in plugin, expected

assert "mic_enabled: bool = True" in model
assert "self.mic_enabled != other.mic_enabled" in model

assert 'data.optBoolean("micEnabled", true)' in client
assert "range, micEnabled" in client
assert "boolean micEnabled" in native_java
assert "rangeBlocks, micEnabled" in native_java

assert "bool micEnabled = true;" in native_cpp
assert "state.micEnabled = micEnabled;" in native_cpp
assert "if (!speaker.micEnabled) return false;" in native_cpp
assert '"(Ljava/lang/String;Ljava/lang/String;DDDFZ)V"' in jni
assert "micEnabled == JNI_TRUE" in jni

print("VC Mumble Item Mic protocol contract: OK")
