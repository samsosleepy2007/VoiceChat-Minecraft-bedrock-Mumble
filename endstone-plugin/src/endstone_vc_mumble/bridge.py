from __future__ import annotations

import hashlib
import hmac
import json
import queue
import secrets
import socket
import threading
import time
from typing import Any


class BridgeServer:
    """Authenticated NDJSON/TCP bridge between Endstone and VC Mumble Server.

    Endstone only enqueues plain dictionaries. Socket I/O stays on this
    background thread so a slow or disconnected Android client cannot block
    the Minecraft server tick.
    """

    def __init__(
        self,
        logger: Any,
        host: str,
        port: int,
        secret: str,
        max_queue: int = 4096,
        max_frame_bytes: int = 262144,
        auth_timeout_seconds: int = 10,
    ) -> None:
        self._logger = logger
        self._host = host
        self._port = int(port)
        self._secret = secret
        self._max_frame_bytes = max(4096, int(max_frame_bytes))
        self._auth_timeout_seconds = max(2, int(auth_timeout_seconds))
        self._outgoing: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=max(128, int(max_queue)))
        self._incoming: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=256)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._state_lock = threading.Lock()
        self._listening = False
        self._client_connected = False
        self._last_error = ""
        self._peer = ""
        self._listener: socket.socket | None = None
        self._client: socket.socket | None = None

    @property
    def listening(self) -> bool:
        with self._state_lock:
            return self._listening

    @property
    def client_connected(self) -> bool:
        with self._state_lock:
            return self._client_connected

    @property
    def last_error(self) -> str:
        with self._state_lock:
            return self._last_error

    @property
    def peer(self) -> str:
        with self._state_lock:
            return self._peer

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="VCMumble-Bridge", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        for sock in (self._client, self._listener):
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=3.0)
        self._thread = None
        self._set_state(False, False, "")

    def send(self, message: dict[str, Any]) -> bool:
        try:
            self._outgoing.put_nowait(dict(message))
            return True
        except queue.Full:
            # Prefer fresh positions over stale ones when the phone/network is slow.
            try:
                self._outgoing.get_nowait()
            except queue.Empty:
                pass
            try:
                self._outgoing.put_nowait(dict(message))
                return True
            except queue.Full:
                return False

    def drain_incoming(self, limit: int = 64) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for _ in range(max(1, limit)):
            try:
                result.append(self._incoming.get_nowait())
            except queue.Empty:
                break
        return result

    def _run(self) -> None:
        listener: socket.socket | None = None
        try:
            listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self._listener = listener
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind((self._host, self._port))
            listener.listen(2)
            listener.settimeout(1.0)
            self._set_state(True, False, "")
            self._record_error("")
            self._logger.info(f"BRIDGE listening tcp={self._host}:{self._port} protocol=1")

            while not self._stop.is_set():
                try:
                    client, address = listener.accept()
                except socket.timeout:
                    continue
                except OSError:
                    if self._stop.is_set():
                        break
                    raise
                self._serve_client(client, address)
        except Exception as exc:
            self._record_error(f"{type(exc).__name__}: {exc}")
            self._logger.error(f"BRIDGE stopped: {type(exc).__name__}: {exc}")
        finally:
            self._client = None
            self._listener = None
            if listener is not None:
                try:
                    listener.close()
                except OSError:
                    pass
            self._set_state(False, False, "")

    def _serve_client(self, client: socket.socket, address: tuple[str, int]) -> None:
        self._client = client
        authenticated = False
        hello_seen = False
        challenge = ""
        buffer = bytearray()
        peer = f"{address[0]}:{address[1]}"
        auth_deadline = time.monotonic() + self._auth_timeout_seconds

        client.settimeout(0.15)
        client.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        try:
            client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass

        try:
            while not self._stop.is_set():
                if not authenticated and time.monotonic() >= auth_deadline:
                    self._send_line(client, {"type": "hello_error", "reason": "authentication timed out"})
                    return

                try:
                    chunk = client.recv(65536)
                    if not chunk:
                        break
                    buffer.extend(chunk)
                    if len(buffer) > self._max_frame_bytes:
                        raise RuntimeError("client frame buffer exceeded limit")

                    while b"\n" in buffer:
                        line, _, rest = buffer.partition(b"\n")
                        buffer = bytearray(rest)
                        if not line.strip():
                            continue
                        if len(line) > self._max_frame_bytes:
                            raise RuntimeError("client frame exceeded limit")
                        try:
                            data = json.loads(line.decode("utf-8"))
                        except (UnicodeDecodeError, json.JSONDecodeError):
                            continue
                        if not isinstance(data, dict):
                            continue

                        if not authenticated:
                            if not hello_seen:
                                if not self._valid_hello(data):
                                    self._send_line(client, {"type": "hello_error", "reason": "invalid hello"})
                                    return
                                hello_seen = True
                                challenge = secrets.token_urlsafe(24)
                                self._send_line(client, {
                                    "type": "auth_challenge",
                                    "protocol": 1,
                                    "nonce": challenge,
                                })
                                continue

                            if not self._valid_auth_response(data, challenge):
                                self._send_line(client, {"type": "hello_error", "reason": "authentication failed"})
                                return

                            authenticated = True
                            self._clear_outgoing()
                            self._set_state(True, True, peer)
                            self._record_error("")
                            self._send_line(client, {
                                "type": "hello_ok",
                                "protocol": 1,
                                "server": "VC Mumble Endstone",
                                "ts": int(time.time() * 1000),
                            })
                            self._put_incoming({"type": "client_connected"})
                            self._logger.info(f"BRIDGE mobile authenticated peer={peer}")
                        else:
                            self._put_incoming(data)
                except socket.timeout:
                    pass

                if authenticated:
                    for _ in range(128):
                        try:
                            payload = self._outgoing.get_nowait()
                        except queue.Empty:
                            break
                        self._send_line(client, payload)
        except (ConnectionError, OSError):
            pass
        except Exception as exc:
            self._record_error(f"{type(exc).__name__}: {exc}")
            self._logger.warning(f"BRIDGE client error peer={peer}: {type(exc).__name__}: {exc}")
        finally:
            try:
                client.close()
            except OSError:
                pass
            self._client = None
            if authenticated:
                self._logger.info(f"BRIDGE mobile disconnected peer={peer}")
                self._put_incoming({"type": "client_disconnected"})
            self._set_state(True, False, "")

    @staticmethod
    def _protocol_of(data: dict[str, Any]) -> int:
        try:
            return int(data.get("protocol", 0) or 0)
        except (TypeError, ValueError):
            return 0

    @classmethod
    def _valid_hello(cls, data: dict[str, Any]) -> bool:
        return (
            data.get("type") == "hello"
            and data.get("role") == "vc_mumble_server"
            and cls._protocol_of(data) == 1
        )

    def _valid_auth_response(self, data: dict[str, Any], nonce: str) -> bool:
        if data.get("type") != "auth_response" or not nonce:
            return False
        supplied = str(data.get("hmac", "")).strip().lower()
        if len(supplied) != 64:
            return False
        message = f"vc-mumble-v1:{nonce}".encode("utf-8")
        expected = hmac.new(self._secret.encode("utf-8"), message, hashlib.sha256).hexdigest()
        return hmac.compare_digest(supplied, expected)

    @staticmethod
    def _send_line(client: socket.socket, payload: dict[str, Any]) -> None:
        encoded = (json.dumps(payload, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")
        client.sendall(encoded)

    def _put_incoming(self, payload: dict[str, Any]) -> None:
        try:
            self._incoming.put_nowait(payload)
        except queue.Full:
            try:
                self._incoming.get_nowait()
            except queue.Empty:
                pass
            try:
                self._incoming.put_nowait(payload)
            except queue.Full:
                pass

    def _clear_outgoing(self) -> None:
        while True:
            try:
                self._outgoing.get_nowait()
            except queue.Empty:
                return

    def _set_state(self, listening: bool, client_connected: bool, peer: str) -> None:
        with self._state_lock:
            self._listening = listening
            self._client_connected = client_connected
            self._peer = peer

    def _record_error(self, error: str) -> None:
        with self._state_lock:
            self._last_error = error
