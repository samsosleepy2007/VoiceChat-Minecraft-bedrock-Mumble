from __future__ import annotations

import json
import math
import secrets
import time
from typing import Any

from endstone import Player
from endstone.command import Command, CommandSender
from endstone.plugin import Plugin

from .bridge import BridgeServer
from .listener import VCMumbleListener
from .model import PlayerState


class VCMumblePlugin(Plugin):
    prefix = "VCMumble"
    version = "0.2.0"
    api_version = "0.11"
    description = "Standalone Minecraft position bridge for VC Mumble Server"
    authors = ["SamSoSleepy"]

    commands = {
        "vcmumble": {
            "description": "VC Mumble pairing, range and bridge status",
            "usages": [
                "/vcmumble",
                "/vcmumble <action: str>",
                "/vcmumble <action: str> <value: str>",
            ],
            "permissions": ["vc_mumble.command.user"],
        },
    }

    permissions = {
        "vc_mumble.command.user": {
            "description": "Use VC Mumble user commands.",
            "default": True,
        },
    }

    def __init__(self) -> None:
        super().__init__()
        self._states: dict[str, PlayerState] = {}
        self._bindings: dict[str, dict[str, Any]] = {}
        self._bridge: BridgeServer | None = None
        self._interval_ticks = 2
        self._position_epsilon = 0.05
        self._rotation_epsilon = 1.0
        self._heartbeat_ticks = 300
        self._heartbeat_accumulator = 0
        self._default_range = 30
        self._max_range = 150
        self._last_client_state: bool | None = None

    def on_enable(self) -> None:
        self.save_default_config()
        self._load_settings()
        self._load_bindings()
        self.register_events(VCMumbleListener(self))
        self.server.scheduler.run_task(self, self._tracking_tick, delay=0, period=self._interval_ticks)
        if self._bridge is not None:
            self._bridge.start()
        self.logger.info(
            f"VC Mumble Endstone v{self.version} enabled; tracking={self._interval_ticks} ticks "
            f"default_range={self._default_range} max_range={self._max_range}"
        )
        self.logger.info("Commands: /vcmumble status | pair <name> | unpair | range <blocks>")

    def on_disable(self) -> None:
        try:
            self.server.scheduler.cancel_tasks(self)
        except Exception:
            pass
        if self._bridge is not None:
            self._bridge.stop()
        self._save_bindings()
        self._states.clear()
        self.logger.info("VC Mumble Endstone disabled")

    def _load_settings(self) -> None:
        tracking = self.config.get("tracking", {})
        bridge = self.config.get("bridge", {})
        voice = self.config.get("voice", {})
        self._interval_ticks = self._bounded_int(tracking.get("interval_ticks", 2), 1, 20, 2)
        self._position_epsilon = self._bounded_float(tracking.get("position_epsilon", 0.05), 0.001, 10.0, 0.05)
        self._rotation_epsilon = self._bounded_float(tracking.get("rotation_epsilon", 1.0), 0.01, 180.0, 1.0)
        heartbeat_seconds = self._bounded_int(tracking.get("heartbeat_seconds", 15), 2, 3600, 15)
        self._heartbeat_ticks = heartbeat_seconds * 20
        self._default_range = self._bounded_int(voice.get("default_range", 30), 1, 1000, 30)
        self._max_range = self._bounded_int(voice.get("max_range", 150), self._default_range, 1000, 150)

        if not bool(bridge.get("enabled", True)):
            self.logger.warning("BRIDGE disabled in config.toml")
            return
        host = str(bridge.get("host", "0.0.0.0")).strip() or "0.0.0.0"
        port = self._bounded_int(bridge.get("port", 27220), 1, 65535, 27220)
        secret = str(bridge.get("secret", "")).strip()
        if not secret:
            secret = secrets.token_urlsafe(32)
            self.config.setdefault("bridge", {})["secret"] = secret
            self.save_config()
            self.logger.warning("BRIDGE secret generated. Read plugins/vc_mumble/config.toml and copy it into VC Mumble Server.")
        max_queue = self._bounded_int(bridge.get("max_queue", 4096), 128, 65536, 4096)
        self._bridge = BridgeServer(self.logger, host, port, secret, max_queue)

    def _load_bindings(self) -> None:
        path = self.data_folder / "bindings.json"
        if not path.is_file():
            self._bindings = {}
            return
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            self._bindings = data if isinstance(data, dict) else {}
        except Exception as exc:
            self._bindings = {}
            self.logger.warning(f"Could not load bindings.json: {type(exc).__name__}: {exc}")

    def _save_bindings(self) -> None:
        try:
            path = self.data_folder / "bindings.json"
            path.write_text(json.dumps(self._bindings, ensure_ascii=False, indent=2), encoding="utf-8")
        except Exception as exc:
            self.logger.warning(f"Could not save bindings.json: {type(exc).__name__}: {exc}")

    def on_command(self, sender: CommandSender, command: Command, args: list[str]) -> bool:
        if command.name != "vcmumble":
            return False
        action = args[0].lower() if args else "status"
        if action == "status":
            return self._command_status(sender)
        if action in ("pair", "unpair", "range"):
            if not isinstance(sender, Player):
                sender.send_error_message("This VC Mumble command must be run by a player.")
                return True
            if action == "pair":
                if len(args) < 2:
                    return False
                return self._command_pair(sender, args[1])
            if action == "unpair":
                return self._command_unpair(sender)
            if action == "range":
                if len(args) < 2:
                    return False
                try:
                    value = int(args[1])
                except ValueError:
                    sender.send_error_message("Voice range must be a number.")
                    return True
                return self._command_range(sender, value)
        return False

    def _command_status(self, sender: CommandSender) -> bool:
        listening = self._bridge.listening if self._bridge is not None else False
        connected = self._bridge.client_connected if self._bridge is not None else False
        sender.send_message(
            f"VC Mumble v{self.version}: bridge={'connected' if connected else ('listening' if listening else 'offline')}, "
            f"tracked={len(self._states)}"
        )
        if isinstance(sender, Player):
            key = self._player_key(sender)
            binding = self._bindings.get(key, {})
            mumble_name = str(binding.get("mumble_name") or sender.name)
            voice_range = int(binding.get("range") or self._default_range)
            sender.send_message(f"Mumble username: {mumble_name} | range: {voice_range} blocks")
        return True

    def _command_pair(self, player: Player, mumble_name: str) -> bool:
        value = mumble_name.strip()
        if not value or len(value) > 64 or any(ord(ch) < 32 for ch in value):
            player.send_error_message("Mumble username must be 1-64 visible characters.")
            return True
        key = self._player_key(player)
        binding = dict(self._bindings.get(key, {}))
        binding["mumble_name"] = value
        binding.setdefault("range", self._default_range)
        self._bindings[key] = binding
        self._save_bindings()
        player.send_message(f"VC Mumble paired: Minecraft {player.name} -> Mumble {value}")
        self._broadcast_current_player(player)
        return True

    def _command_unpair(self, player: Player) -> bool:
        key = self._player_key(player)
        old = self._bindings.get(key)
        if old is None:
            player.send_message("VC Mumble was already using your Minecraft name automatically.")
            return True
        current_range = int(old.get("range") or self._default_range)
        if current_range == self._default_range:
            self._bindings.pop(key, None)
        else:
            self._bindings[key] = {"range": current_range}
        self._save_bindings()
        player.send_message(f"VC Mumble unpaired. Mumble username is now {player.name}.")
        self._broadcast_current_player(player)
        return True

    def _command_range(self, player: Player, value: int) -> bool:
        if value < 1 or value > self._max_range:
            player.send_error_message(f"Voice range must be 1-{self._max_range} blocks.")
            return True
        key = self._player_key(player)
        binding = dict(self._bindings.get(key, {}))
        binding["range"] = value
        self._bindings[key] = binding
        self._save_bindings()
        player.send_message(f"VC Mumble voice range set to {value} blocks.")
        self._broadcast_current_player(player)
        return True

    def handle_player_join(self, player: Player) -> None:
        state = self._snapshot_if_valid(player)
        if state is None:
            return
        self._states[self._player_key(player)] = state
        self._send_state(state)

    def handle_player_quit(self, player: Player) -> None:
        key = self._player_key(player)
        state = self._states.pop(key, None)
        self._bridge_send({
            "type": "player_leave",
            "name": state.name if state else str(player.name),
            "xuid": str(player.xuid or ""),
            "uuid": str(player.unique_id),
            "mumbleName": self._mumble_name_for(key, str(player.name)),
        })

    def _tracking_tick(self) -> None:
        self._drain_bridge_commands()
        current_keys: set[str] = set()
        for player in self.server.online_players:
            key = self._player_key(player)
            current_keys.add(key)
            state = self._snapshot_if_valid(player)
            if state is None:
                continue
            previous = self._states.get(key)
            if previous is None or state.changed_from(previous, self._position_epsilon, self._rotation_epsilon):
                self._states[key] = state
                self._send_state(state)

        for stale_key in set(self._states).difference(current_keys):
            stale = self._states.pop(stale_key)
            self._bridge_send({
                "type": "player_leave",
                "name": stale.name,
                "xuid": stale.xuid,
                "uuid": stale.uuid,
                "mumbleName": self._mumble_name_for(stale_key, stale.name),
            })

        connected = self._bridge.client_connected if self._bridge is not None else False
        if connected != self._last_client_state:
            self._last_client_state = connected
            self.logger.info(f"BRIDGE STATUS mobile={'connected' if connected else 'disconnected'}")

        self._heartbeat_accumulator += self._interval_ticks
        if self._heartbeat_accumulator >= self._heartbeat_ticks:
            self._heartbeat_accumulator = 0
            self._bridge_send({
                "type": "heartbeat",
                "online": len(current_keys),
                "tracked": len(self._states),
                "ts": int(time.time() * 1000),
            })

    def _drain_bridge_commands(self) -> None:
        if self._bridge is None:
            return
        for message in self._bridge.drain_incoming():
            kind = str(message.get("type", ""))
            if kind in ("request_snapshot", "client_connected"):
                self._send_full_snapshot()

    def _send_full_snapshot(self) -> None:
        self._bridge_send({"type": "sync_begin", "count": len(self._states)})
        for key, state in self._states.items():
            self._bridge_send(self._state_message(key, state))
        self._bridge_send({"type": "sync_end", "count": len(self._states)})

    def _broadcast_current_player(self, player: Player) -> None:
        state = self._snapshot_if_valid(player)
        if state is not None:
            key = self._player_key(player)
            self._states[key] = state
            self._send_state(state)

    def _send_state(self, state: PlayerState) -> None:
        key = state.xuid if state.xuid else state.uuid
        self._bridge_send(self._state_message(key, state))

    def _state_message(self, key: str, state: PlayerState) -> dict[str, Any]:
        binding = self._bindings.get(key, {})
        return {
            "type": "player_state",
            "name": state.name,
            "xuid": state.xuid,
            "uuid": state.uuid,
            "mumbleName": str(binding.get("mumble_name") or state.name),
            "dimension": state.dimension,
            "x": state.x,
            "y": state.y,
            "z": state.z,
            "yaw": state.yaw,
            "pitch": state.pitch,
            "voiceRange": int(binding.get("range") or self._default_range),
        }

    def _bridge_send(self, message: dict[str, Any]) -> bool:
        return self._bridge.send(message) if self._bridge is not None else False

    def _mumble_name_for(self, key: str, fallback: str) -> str:
        return str(self._bindings.get(key, {}).get("mumble_name") or fallback)

    @staticmethod
    def _player_key(player: Player) -> str:
        return str(player.xuid or "") or str(player.unique_id)

    def _snapshot_if_valid(self, player: Player) -> PlayerState | None:
        try:
            loc = player.location
            state = PlayerState(
                name=str(player.name),
                xuid=str(player.xuid or ""),
                uuid=str(player.unique_id),
                dimension=str(player.dimension.name),
                x=float(loc.x), y=float(loc.y), z=float(loc.z),
                yaw=float(loc.yaw), pitch=float(loc.pitch),
            )
            values = (state.x, state.y, state.z, state.yaw, state.pitch)
            if not all(math.isfinite(v) for v in values):
                return None
            if state.y < -4096.0 or state.y > 4096.0:
                return None
            if abs(state.x) > 30_000_000 or abs(state.z) > 30_000_000:
                return None
            return state if state.dimension else None
        except Exception:
            return None

    @staticmethod
    def _bounded_int(value: Any, minimum: int, maximum: int, fallback: int) -> int:
        try:
            return max(minimum, min(maximum, int(value)))
        except (TypeError, ValueError):
            return fallback

    @staticmethod
    def _bounded_float(value: Any, minimum: float, maximum: float, fallback: float) -> float:
        try:
            return max(minimum, min(maximum, float(value)))
        except (TypeError, ValueError):
            return fallback
