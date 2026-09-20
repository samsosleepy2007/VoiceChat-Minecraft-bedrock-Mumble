from pathlib import Path

root = Path(__file__).resolve().parents[1]
client = (root / 'app/src/main/java/com/voicecraft/vcmumbleserver/VCMumbleBridgeClient.java').read_text()
config = (root / 'app/src/main/java/com/voicecraft/vcmumbleserver/ServerConfig.java').read_text()
core_service = (root / 'app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java').read_text()
plugin = (root / 'endstone-plugin/src/endstone_vc_mumble/plugin.py').read_text()
bridge = (root / 'endstone-plugin/src/endstone_vc_mumble/bridge.py').read_text()
pyproject = (root / 'endstone-plugin/pyproject.toml').read_text()
real_jni = (root / 'app/src/main/cpp/mumble_jni.cpp').read_text()
smoke_jni = (root / 'app/src/main/cpp/transport_smoketest_jni.cpp').read_text()
proximity = (root / 'native/mumble_android/VCProximity.cpp').read_text()

for expected in [
    'hello.put("role", "vc_mumble_server")',
    'hello.put("protocol", 1)',
    '"auth_challenge".equals(type)',
    'computeHmac(config.bridgeSecret, "vc-mumble-v1:" + nonce)',
    'request.put("type", "request_snapshot")',
    'case "player_state"',
    'case "player_leave"',
    'case "sync_begin"',
    'case "sync_end"',
    'data.optString("mumbleName", minecraftName)',
    'data.optInt("voiceRange", config.voiceRange)',
    'data.optBoolean("voiceEnabled", true)',
    'NativeServer.updatePlayerState',
    'NativeServer.removePlayerState',
]:
    assert expected in client, expected

assert 'bridge_host' in config
assert 'bridge_port' in config
assert 'VoiceCraft' not in config
assert 'NativeServer.setProximityEnabled(proximityActive)' in core_service
assert 'VCMumbleBridgeClient' in core_service

for expected in [
    '"type": "player_state"',
    '"mumbleName"',
    '"voiceRange"',
    '"voiceEnabled"',
    '"type": "sync_begin"',
    '"type": "sync_end"',
    '"type": "player_leave"',
    'data.get("role") == "vc_mumble_server"',
    'data.get("type") != "auth_response"',
    'hmac.compare_digest(supplied, expected)',
    'max_frame_bytes',
    'auth_timeout_seconds',
    '_clear_outgoing()',
]:
    assert expected in plugin + bridge, expected

assert 'name = "endstone-vc-mumble"' in pyproject
# Runtime plugin metadata and Python distribution version stay aligned.
assert 'version = "0.3.0"' in pyproject
assert 'version = "0.3.0"' in plugin
assert 'vc-mumble = "endstone_vc_mumble:VCMumblePlugin"' in pyproject

# The real core uses explicit RegisterNatives binding from JNI_OnLoad so ART
# does not have to discover Java_com_* symbols in the Qt-loaded main library.
for expected in [
    'RegisterNatives',
    'FindClass("com/voicecraft/vcmumbleserver/NativeServer")',
    '"setProximityEnabledNative"',
    '"setProximityStaleTimeoutMsNative"',
    '"updatePlayerStateNative"',
    '"removePlayerStateNative"',
    '"clearPlayerStatesNative"',
    '"proximityPlayerCountNative"',
]:
    assert expected in real_jni, expected

# The lightweight smoke target still uses conventional Java_com_* exports.
assert '(Ljava/lang/String;Ljava/lang/String;DDDFZ)V' in real_jni
assert 'voiceEnabled == JNI_TRUE' in real_jni
assert 'if (!speaker.voiceEnabled) return false;' in proximity
assert 'NativeServer_setProximityEnabledNative' in smoke_jni
for symbol in ['updatePlayerState', 'removePlayerState', 'clearPlayerStates', 'proximityPlayerCount']:
    assert f'NativeServer_{symbol}' in smoke_jni, symbol

print('VC Mumble standalone bridge contract static test: OK')
