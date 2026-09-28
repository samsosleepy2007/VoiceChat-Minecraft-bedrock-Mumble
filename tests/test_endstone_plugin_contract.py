from __future__ import annotations

import re
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_PATH = ROOT / "endstone-plugin/src/endstone_vc_mumble/plugin.py"
HOST_PATH = ROOT / "endstone-plugin/src/endstone_vc_mumble/host.py"
STATE_PATH = ROOT / "endstone-plugin/src/endstone_vc_mumble/local_state.py"
CONFIG_PATH = ROOT / "endstone-plugin/src/endstone_vc_mumble/config.toml"
PYPROJECT_PATH = ROOT / "endstone-plugin/pyproject.toml"

plugin = PLUGIN_PATH.read_text(encoding="utf-8")
host = HOST_PATH.read_text(encoding="utf-8")
state = STATE_PATH.read_text(encoding="utf-8")
config = tomllib.loads(CONFIG_PATH.read_text(encoding="utf-8"))
pyproject = tomllib.loads(PYPROJECT_PATH.read_text(encoding="utf-8"))

runtime_version = re.search(
    r'^\s*version\s*=\s*"([^"]+)"',
    plugin,
    re.MULTILINE,
)
assert runtime_version, "plugin runtime version missing"
assert pyproject["project"]["version"] == runtime_version.group(1) == "0.5.0"

assert config["tracking"]["interval_ticks"] == 2
assert config["tracking"]["heartbeat_seconds"] >= 2
assert config["mumble"]["port"] == 18655
assert config["mumble"]["users"] == 20
assert config["local_state"]["host"] == "127.0.0.1"
assert config["local_state"]["port"] == 47855
assert config["local_state"]["max_queue"] >= 128
assert config["voice"]["default_range"] == 30
assert config["voice"]["max_range"] >= config["voice"]["default_range"]
assert config["voice"]["default_attenuation_level"] == 3
assert "bridge" not in config

for expected in [
    "MumbleRuntimeHost",
    "LocalStateSink",
    '"voiceEnabled"',
    '"voiceRange"',
    '"attenuationLevel"',
    '"mumbleName": state.name',
    '"vcmumble.mic.on"',
    '"vcmumble.mic.off"',
    "vcmumble.vr.ack.",
    "vcmumble.vr.sync.",
    "vcmumble.vr.request.",
    "vcmumble.attn.value.",
    "vcmumble.attn.ack.",
    "vcmumble.attn.sync.",
    '"vcb"',
    "ActionForm",
    '"vc_mumble.command.admin"',
    '"default": "op"',
    "_send_full_snapshot",
    "host_running and not self._last_host_running",
    "Unified MCSV mode active",
]:
    assert expected in plugin, expected

for forbidden in [
    "BridgeServer",
    "_bridge_send",
    "_drain_bridge_commands",
    "client_connected",
    "auth_challenge",
    "bridge secret",
    "_show_pair_form",
    "_command_pair",
    "_command_unpair",
]:
    assert forbidden not in plugin, forbidden

for expected in [
    "mumble-server-vc",
    "mumblevoip/mumble-server",
    "MUMBLE_PLATFORM_MANIFEST",
    "subprocess.Popen",
    "LD_LIBRARY_PATH",
    "stage=running",
]:
    assert expected in host, expected

for expected in [
    "socket.SOCK_DGRAM",
    "queue.Queue",
    "put_nowait",
    "sock.sendto",
    "127.0.0.1",
]:
    assert expected in state, expected

assert 'endstone_vc_mumble = ["config.toml", "bin/mumble-server-vc"]' in (
    PYPROJECT_PATH.read_text(encoding="utf-8")
)

print("VC Mumble unified MCSV plugin contract test: OK")
