from __future__ import annotations

import importlib.util
import json
import socket
import sys
import threading
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_DIR = ROOT / "endstone-plugin"
VENDOR = PLUGIN_DIR / "vendor" / "endstone_mumble_host-0.3.0-py3-none-any.whl"
PREPARE = PLUGIN_DIR / "prepare_unified_runtime.py"
STATE_PATH = PLUGIN_DIR / "src/endstone_vc_mumble/local_state.py"


assert VENDOR.is_file()
with zipfile.ZipFile(VENDOR) as archive:
    names = set(archive.namelist())
    member = "endstone_mumble_host/bin/mumble-server-vc"
    assert member in names
    assert len(archive.read(member)) > 2_000_000

prepare_source = PREPARE.read_text(encoding="utf-8")
assert "endstone_mumble_host/bin/mumble-server-vc" in prepare_source
assert 'TARGET.chmod(0o755)' in prepare_source

spec = importlib.util.spec_from_file_location(
    "vc_mumble_local_state_contract",
    STATE_PATH,
)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
LocalStateSink = module.LocalStateSink


class DummyLogger:
    def info(self, *_args, **_kwargs):
        pass

    def warning(self, *_args, **_kwargs):
        pass

    def error(self, *_args, **_kwargs):
        pass


receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
receiver.bind(("127.0.0.1", 0))
receiver.settimeout(2.0)
port = int(receiver.getsockname()[1])

sink = LocalStateSink(
    DummyLogger(),
    host="127.0.0.1",
    port=port,
    max_queue=128,
)
sink.start()

payload = {
    "type": "player_state",
    "name": "Alice",
    "mumbleName": "Alice",
    "dimension": "Overworld",
    "x": 1.0,
    "y": 64.0,
    "z": 2.0,
    "voiceRange": 30,
    "voiceEnabled": True,
    "attenuationLevel": 3,
}
assert sink.send(payload)

data, peer = receiver.recvfrom(65535)
decoded = json.loads(data.decode("utf-8"))
assert decoded == payload
assert peer[0] == "127.0.0.1"

sink.stop()
receiver.close()
assert sink.last_error == ""

print("VC Mumble unified local-host integration test: OK")
