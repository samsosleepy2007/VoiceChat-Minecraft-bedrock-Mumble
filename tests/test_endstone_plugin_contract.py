from __future__ import annotations

import hashlib
import hmac
import importlib.util
import re
import sys
import tomllib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
plugin_path = root / "endstone-plugin/src/endstone_vc_mumble/plugin.py"
bridge_path = root / "endstone-plugin/src/endstone_vc_mumble/bridge.py"
config_path = root / "endstone-plugin/src/endstone_vc_mumble/config.toml"
pyproject_path = root / "endstone-plugin/pyproject.toml"

plugin = plugin_path.read_text(encoding="utf-8")
bridge_source = bridge_path.read_text(encoding="utf-8")
config = tomllib.loads(config_path.read_text(encoding="utf-8"))
pyproject = tomllib.loads(pyproject_path.read_text(encoding="utf-8"))

runtime_version = re.search(r'^\s*version\s*=\s*"([^"]+)"', plugin, re.MULTILINE)
assert runtime_version, "plugin runtime version missing"
assert pyproject["project"]["version"] == runtime_version.group(1) == "0.3.0"

assert config["tracking"]["interval_ticks"] == 2
assert config["tracking"]["heartbeat_seconds"] >= 2
assert config["bridge"]["port"] == 27220
assert config["bridge"]["max_queue"] >= 128
assert config["bridge"]["max_frame_bytes"] >= 4096
assert 2 <= config["bridge"]["auth_timeout_seconds"] <= 60
assert config["voice"]["default_range"] == 30
assert config["voice"]["max_range"] >= config["voice"]["default_range"]

for expected in [
    'remove_scoreboard_tag',
    'add_scoreboard_tag',
    'scoreboard_tags',
    '"voiceEnabled"',
    "vcmumble.vr.ack.",
    "vcmumble.vr.sync.",
    "vcmumble.vr.request.",
    '"vcmumble.mic.on"',
    '"vcmumble.mic.off"',
    '"vcmumbleadmin"',
    '"vc_mumble.command.admin"',
    '"default": "op"',
    'action == "players"',
    'action == "resync"',
    'action == "reload"',
    'action == "range"',
    '"voiceRange"',
    '"dimension"',
    '"mumbleName"',
]:
    assert expected in plugin, expected

for expected in [
    "socket.SO_KEEPALIVE",
    "socket.TCP_NODELAY",
    "_max_frame_bytes",
    "_auth_timeout_seconds",
    "_clear_outgoing()",
    'reason": "authentication timed out"',
    "hmac.compare_digest",
]:
    assert expected in bridge_source, expected

spec = importlib.util.spec_from_file_location("vc_mumble_bridge_contract_module", bridge_path)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
BridgeServer = module.BridgeServer


class DummyLogger:
    def info(self, *_args, **_kwargs):
        pass

    def warning(self, *_args, **_kwargs):
        pass

    def error(self, *_args, **_kwargs):
        pass


bridge = BridgeServer(
    DummyLogger(),
    "127.0.0.1",
    27220,
    "unit-test-secret",
    max_queue=128,
    max_frame_bytes=4096,
    auth_timeout_seconds=2,
)

assert bridge._valid_hello({"type": "hello", "role": "vc_mumble_server", "protocol": 1})
assert not bridge._valid_hello({"type": "hello", "role": "vc_mumble_server", "protocol": "bad"})
assert not bridge._valid_hello({"type": "hello", "role": "other", "protocol": 1})

nonce = "test-nonce"
expected_hmac = hmac.new(
    b"unit-test-secret",
    f"vc-mumble-v1:{nonce}".encode("utf-8"),
    hashlib.sha256,
).hexdigest()
assert bridge._valid_auth_response({"type": "auth_response", "hmac": expected_hmac}, nonce)
assert not bridge._valid_auth_response({"type": "auth_response", "hmac": "00"}, nonce)
assert not bridge._valid_auth_response({"type": "wrong", "hmac": expected_hmac}, nonce)

bridge.send({"type": "stale"})
bridge.send({"type": "stale2"})
bridge._clear_outgoing()
assert bridge._outgoing.empty()

print("VC Mumble Endstone plugin contract test: OK")
