from __future__ import annotations

import hashlib
import hmac
import importlib.util
import json
import socket
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE_PATH = ROOT / "endstone-plugin/src/endstone_vc_mumble/bridge.py"

spec = importlib.util.spec_from_file_location("vc_mumble_bridge_integration_module", BRIDGE_PATH)
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


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def send_line(sock: socket.socket, payload: dict) -> None:
    sock.sendall((json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8"))


def read_line(sock_file) -> dict:
    line = sock_file.readline()
    assert line, "bridge closed unexpectedly"
    return json.loads(line.decode("utf-8"))


def wait_listening(bridge: BridgeServer, timeout: float = 3.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if bridge.listening:
            return
        time.sleep(0.02)
    raise AssertionError(f"bridge did not start: {bridge.last_error}")


def authenticate(port: int, secret: str):
    sock = socket.create_connection(("127.0.0.1", port), timeout=2.0)
    sock.settimeout(2.0)
    stream = sock.makefile("rb")
    send_line(sock, {"type": "hello", "role": "vc_mumble_server", "protocol": 1})
    challenge = read_line(stream)
    assert challenge["type"] == "auth_challenge"
    nonce = challenge["nonce"]
    digest = hmac.new(
        secret.encode("utf-8"),
        f"vc-mumble-v1:{nonce}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    send_line(sock, {"type": "auth_response", "hmac": digest})
    hello_ok = read_line(stream)
    assert hello_ok["type"] == "hello_ok"
    assert hello_ok["protocol"] == 1
    return sock, stream


def wait_incoming_types(bridge: BridgeServer, expected: set[str], timeout: float = 2.0) -> list[dict]:
    deadline = time.monotonic() + timeout
    received: list[dict] = []
    while time.monotonic() < deadline:
        received.extend(bridge.drain_incoming())
        kinds = {str(item.get("type", "")) for item in received}
        if expected.issubset(kinds):
            return received
        time.sleep(0.02)
    raise AssertionError(f"missing incoming events {expected}; got {received}")


port = free_port()
secret = "integration-secret"
bridge = BridgeServer(
    DummyLogger(),
    "127.0.0.1",
    port,
    secret,
    max_queue=128,
    max_frame_bytes=4096,
    auth_timeout_seconds=2,
)

# Anything queued before a fresh authenticated connection is stale and must not
# leak into the new Android session.
bridge.send({"type": "stale_before_connect", "value": 1})
bridge.start()
wait_listening(bridge)

sock, stream = authenticate(port, secret)
events = wait_incoming_types(bridge, {"client_connected", "request_snapshot"})
assert bridge.client_connected
assert bridge.peer.startswith("127.0.0.1:")

fresh_state = {
    "type": "player_state",
    "name": "Alice",
    "xuid": "1",
    "uuid": "u1",
    "mumbleName": "Alice",
    "dimension": "Overworld",
    "x": 1.0,
    "y": 64.0,
    "z": 2.0,
    "yaw": 0.0,
    "pitch": 0.0,
    "voiceRange": 30,
}
assert bridge.send({"type": "sync_begin", "count": 1})
assert bridge.send(fresh_state)
assert bridge.send({"type": "sync_end", "count": 1})

first = read_line(stream)
second = read_line(stream)
third = read_line(stream)
assert [first["type"], second["type"], third["type"]] == ["sync_begin", "player_state", "sync_end"]
assert second["mumbleName"] == "Alice"
assert second["voiceRange"] == 30
assert second["dimension"] == "Overworld"
assert first.get("type") != "stale_before_connect"

# Android can ask for a resnapshot at any time.
send_line(sock, {"type": "request_snapshot"})
wait_incoming_types(bridge, {"request_snapshot"})

sock.close()
deadline = time.monotonic() + 2.0
while time.monotonic() < deadline and bridge.client_connected:
    time.sleep(0.02)

# A second Android session must authenticate independently and reconnect cleanly.
sock2, stream2 = authenticate(port, secret)
wait_incoming_types(bridge, {"client_connected", "request_snapshot"})
assert bridge.client_connected
sock2.close()

bridge.stop()
assert not bridge.listening
assert not bridge.client_connected

print("VC Mumble bridge TCP integration test: OK")
