from pathlib import Path

root = Path(__file__).resolve().parents[1]

main = (root / "app/src/main/java/com/voicecraft/vcmumbleserver/MainActivity.java").read_text(encoding="utf-8")
service = (root / "app/src/core/java/com/voicecraft/vcmumbleserver/MumbleServerService.java").read_text(encoding="utf-8")
config = (root / "app/src/main/java/com/voicecraft/vcmumbleserver/ServerConfig.java").read_text(encoding="utf-8")
secret = (root / "app/src/main/java/com/voicecraft/vcmumbleserver/SecretStore.java").read_text(encoding="utf-8")

# Config is consumed immediately by the isolated :mumble process, so persistence
# must be synchronous before Android is asked to start that process.
assert ".commit();" in config
assert "Could not persist server settings" in config
assert ".apply();" not in config

assert ".commit()" in secret
assert "Could not persist bridge secret" in secret
assert ".apply()" not in secret

# Regression: editing Minecraft Server / Bridge settings and immediately tapping
# Start must behave the same as Quick Start; the isolated :mumble process must
# never depend on an asynchronous settings flush.
# Normal Start and Quick Start share the same launch path. Quick Start may set
# defaults, but it must not pre-save through a separate path that hides races.
quick_start = main[main.index('Button quick = secondaryButton("ใช้ค่าเริ่มต้นและเปิด");'):]
quick_start = quick_start[:quick_start.index("root.addView(quick")]
assert "saveSettings(false)" not in quick_start
assert "toggleServer()" in quick_start

# A start request is visible immediately, Android service dispatch is logged,
# and a rejected foreground-service start is surfaced instead of silently doing
# nothing after the "Start server" log line.
for expected in [
    'updateState(true, "Starting • " + targetAddress',
    '"Dispatching Mumble service start; target="',
    '"Mumble service start dispatched; component="',
    "startForegroundService(i)",
    "ComponentName component",
    'ServerRuntimeState.setShouldRun(this, false);',
    '"เปิด Mumble Service ไม่สำเร็จ: "',
]:
    assert expected in main, expected

# Minecraft bridge connection is intentionally delayed until the Mumble TCP
# listener has passed its localhost readiness probe.
ready_marker = "if (tcpReady) {"
ready_index = service.index(ready_marker)
relay_index = service.index("startRelay(config)", ready_index)
assert relay_index > ready_index
assert service.index("mumbleReady = true;", ready_index) < relay_index

pre_ready = service[:ready_index]
assert "startRelay(config)" not in pre_ready

for expected in [
    'bridgeStatus = config.hasUsableBridgeConfig()',
    '"Minecraft bridge waiting for Mumble"',
    'publish(true, mumbleReady ? serverAddress : "Starting • " + serverAddress)',
]:
    assert expected in service, expected

print("Android start/bridge independence regression test: OK")
