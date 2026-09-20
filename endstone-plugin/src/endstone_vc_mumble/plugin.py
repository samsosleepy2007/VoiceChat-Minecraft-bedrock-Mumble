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
    version = "0.3.0"
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
        "vcmumbleadmin": {
            "description": "VC Mumble bridge administration",
            "usages": [
                "/vcmumbleadmin",
                "/vcmumbleadmin <action: str>",
                "/vcmumbleadmin <action: str> <value: str>",
                "/vcmumbleadmin <action: str> <target: str> <value: str>",
            ],
            "permissions": ["vc_mumble.command.admin"],
        },
    }

    permissions = {
        "vc_mumble.command.user": {
            "description": "Use VC Mumble user commands.",
            "default": True,
        },
        "vc_mumble.command.admin": {
            "description": "Administer VC Mumble bridge and player ranges.",
            "default": "op",
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

    MIC_ON_TAG = "vcmumble.mic.on"
    MIC_OFF_TAG = "vcmumble.mic.off"
    RANGE_VALUE_PREFIX = "vcmumble.vr.value."
    RANGE_REQUEST_PREFIX = "vcmumble.vr.request."
    RANGE_MAX_PREFIX = "vcmumble.vr.max."
    RANGE_ACK_PREFIX = "vcmumble.vr.ack."
    RANGE_SYNC_PREFIX = "vcmumble.vr.sync."
    PAIR_VALUE_PREFIX = "vcmumble.pair.value."
    PAIR_REQUEST_PREFIX = "vcmumble.pair.request."
    PAIR_UNPAIR_PREFIX = "vcmumble.pair.unpair."
    PAIR_ACK_PREFIX = "vcmumble.pair.ack."
    PAIR_SYNC_PREFIX = "vcmumble.pair.sync."

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
        self.logger.info("Admin: /vcmumbleadmin status | players | resync | reload | range <player> <blocks>")

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
        max_frame_bytes = self._bounded_int(bridge.get("max_frame_bytes", 262144), 4096, 1048576, 262144)
        auth_timeout_seconds = self._bounded_int(bridge.get("auth_timeout_seconds", 10), 2, 60, 10)
        self._bridge = BridgeServer(
            self.logger,
            host,
            port,
            secret,
            max_queue=max_queue,
            max_frame_bytes=max_frame_bytes,
            auth_timeout_seconds=auth_timeout_seconds,
        )

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
        if command.name == "vcmumbleadmin":
            return self._command_admin(sender, args)
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

    def _command_admin(self, sender: CommandSender, args: list[str]) -> bool:
        if not sender.has_permission("vc_mumble.command.admin"):
            sender.send_error_message("You do not have permission to administer VC Mumble.")
            return True

        action = args[0].lower() if args else "status"
        if action == "status":
            bridge = self._bridge
            listening = bridge.listening if bridge is not None else False
            connected = bridge.client_connected if bridge is not None else False
            peer = bridge.peer if bridge is not None else ""
            last_error = bridge.last_error if bridge is not None else ""
            state = "connected" if connected else ("listening" if listening else "offline")
            sender.send_message(
                f"VC Mumble admin: bridge={state}, tracked={len(self._states)}, "
                f"default_range={self._default_range}, max_range={self._max_range}"
            )
            if peer:
                sender.send_message(f"Bridge peer: {peer}")
            if last_error:
                sender.send_message(f"Last bridge error: {last_error}")
            return True

        if action == "players":
            if not self._states:
                sender.send_message("VC Mumble: no players are currently tracked.")
                return True
            sender.send_message(f"VC Mumble tracked players ({len(self._states)}):")
            for key, state in sorted(self._states.items(), key=lambda item: item[1].name.lower()):
                binding = self._bindings.get(key, {})
                mumble_name = str(binding.get("mumble_name") or state.name)
                voice_range = int(binding.get("range") or self._default_range)
                sender.send_message(
                    f"- {state.name} -> {mumble_name} | {voice_range} blocks | {state.dimension}"
                )
            return True

        if action == "resync":
            self._send_full_snapshot()
            sender.send_message(f"VC Mumble snapshot queued for {len(self._states)} tracked players.")
            return True

        if action == "reload":
            old_bridge = self._bridge
            self._bridge = None
            if old_bridge is not None:
                old_bridge.stop()
            try:
                self.reload_config()
                self._load_settings()
                if self._bridge is not None:
                    self._bridge.start()
                sender.send_message("VC Mumble config reloaded and bridge restarted.")
            except Exception as exc:
                sender.send_error_message(f"VC Mumble reload failed: {type(exc).__name__}: {exc}")
            return True

        if action == "range":
            if len(args) < 3:
                sender.send_error_message("Usage: /vcmumbleadmin range <player> <blocks>")
                return True
            target = self._find_online_player(args[1])
            if target is None:
                sender.send_error_message(f"Player not found: {args[1]}")
                return True
            try:
                value = int(args[2])
            except ValueError:
                sender.send_error_message("Voice range must be a number.")
                return True
            if value < 1 or value > self._max_range:
                sender.send_error_message(f"Voice range must be 1-{self._max_range} blocks.")
                return True
            key = self._player_key(target)
            binding = dict(self._bindings.get(key, {}))
            binding["range"] = value
            self._bindings[key] = binding
            self._save_bindings()
            self._broadcast_current_player(target)
            sender.send_message(f"VC Mumble range for {target.name} set to {value} blocks.")
            target.send_message(f"Your VC Mumble voice range is now {value} blocks.")
            return True

        sender.send_error_message("Actions: status, players, resync, reload, range")
        return True

    def _find_online_player(self, name: str) -> Player | None:
        wanted = name.strip().lower()
        if not wanted:
            return None
        for player in self.server.online_players:
            if str(player.name).lower() == wanted:
                return player
        return None

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
            self._process_addon_requests(player)
            self._publish_addon_state(player)
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
            if kind == "request_snapshot":
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
            "micEnabled": bool(state.mic_enabled),
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
                mic_enabled=self._mic_enabled_for(player),
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

    def _scoreboard_tags(self, player: Player) -> list[str]:
        try:
            return list(player.scoreboard_tags)
        except Exception:
            return []

    def _remove_tag(self, player: Player, tag: str) -> None:
        try:
            player.remove_scoreboard_tag(tag)
        except Exception:
            pass

    def _add_tag(self, player: Player, tag: str) -> None:
        try:
            player.add_scoreboard_tag(tag)
        except Exception:
            pass

    def _clear_tags_with_prefix(self, player: Player, prefix: str) -> None:
        for tag in self._scoreboard_tags(player):
            if tag.startswith(prefix):
                self._remove_tag(player, tag)

    def _set_value_tag(self, player: Player, prefix: str, value: str) -> None:
        wanted = prefix + value
        found = False
        for tag in self._scoreboard_tags(player):
            if not tag.startswith(prefix):
                continue
            if tag == wanted and not found:
                found = True
                continue
            self._remove_tag(player, tag)
        if not found:
            self._add_tag(player, wanted)

    def _mic_enabled_for(self, player: Player) -> bool:
        tags = set(self._scoreboard_tags(player))
        if self.MIC_OFF_TAG in tags:
            return False
        if self.MIC_ON_TAG in tags:
            return True
        # Backwards compatibility: players without the Item Mic addon keep
        # normal Mumble speech routing.
        return True

    def _publish_addon_state(self, player: Player) -> None:
        key = self._player_key(player)
        binding = self._bindings.get(key, {})
        voice_range = int(binding.get("range") or self._default_range)
        mumble_name = str(binding.get("mumble_name") or player.name)
        self._set_value_tag(player, self.RANGE_VALUE_PREFIX, str(voice_range))
        self._set_value_tag(player, self.RANGE_MAX_PREFIX, str(self._max_range))
        if self._is_addon_safe_mumble_name(mumble_name):
            self._set_value_tag(player, self.PAIR_VALUE_PREFIX, mumble_name)

    def _process_addon_requests(self, player: Player) -> None:
        key = self._player_key(player)
        tags = self._scoreboard_tags(player)

        for tag in tags:
            if tag.startswith(self.RANGE_REQUEST_PREFIX):
                payload = tag[len(self.RANGE_REQUEST_PREFIX):]
                request_id, dot, raw_value = payload.partition(".")
                self._remove_tag(player, tag)
                if not request_id or not dot:
                    continue
                try:
                    requested = int(raw_value)
                except ValueError:
                    requested = 0
                accepted = max(1, min(self._max_range, requested)) if requested >= 1 else 0
                status = "ok" if accepted == requested and accepted >= 1 else "error"
                if accepted >= 1:
                    binding = dict(self._bindings.get(key, {}))
                    binding["range"] = accepted
                    self._bindings[key] = binding
                    self._save_bindings()
                    self._broadcast_current_player(player)
                current = int(self._bindings.get(key, {}).get("range") or self._default_range)
                self._clear_tags_with_prefix(player, self.RANGE_ACK_PREFIX + request_id + ".")
                self._add_tag(player, f"{self.RANGE_ACK_PREFIX}{request_id}.{status}.{current}")

            elif tag.startswith(self.RANGE_SYNC_PREFIX):
                request_id = tag[len(self.RANGE_SYNC_PREFIX):]
                self._remove_tag(player, tag)
                if request_id:
                    current = int(self._bindings.get(key, {}).get("range") or self._default_range)
                    self._clear_tags_with_prefix(player, self.RANGE_ACK_PREFIX + request_id + ".")
                    self._add_tag(player, f"{self.RANGE_ACK_PREFIX}{request_id}.ok.{current}")

            elif tag.startswith(self.PAIR_REQUEST_PREFIX):
                payload = tag[len(self.PAIR_REQUEST_PREFIX):]
                request_id, dot, mumble_name = payload.partition(".")
                self._remove_tag(player, tag)
                if not request_id or not dot:
                    continue
                status = "error"
                if self._is_addon_safe_mumble_name(mumble_name):
                    binding = dict(self._bindings.get(key, {}))
                    binding["mumble_name"] = mumble_name
                    binding.setdefault("range", self._default_range)
                    self._bindings[key] = binding
                    self._save_bindings()
                    self._broadcast_current_player(player)
                    status = "ok"
                current = self._mumble_name_for(key, str(player.name))
                self._clear_tags_with_prefix(player, self.PAIR_ACK_PREFIX + request_id + ".")
                if self._is_addon_safe_mumble_name(current):
                    self._add_tag(player, f"{self.PAIR_ACK_PREFIX}{request_id}.{status}.{current}")
                else:
                    self._add_tag(player, f"{self.PAIR_ACK_PREFIX}{request_id}.{status}")

            elif tag.startswith(self.PAIR_UNPAIR_PREFIX):
                request_id = tag[len(self.PAIR_UNPAIR_PREFIX):]
                self._remove_tag(player, tag)
                if not request_id:
                    continue
                old = self._bindings.get(key, {})
                current_range = int(old.get("range") or self._default_range)
                if current_range == self._default_range:
                    self._bindings.pop(key, None)
                else:
                    self._bindings[key] = {"range": current_range}
                self._save_bindings()
                self._broadcast_current_player(player)
                self._clear_tags_with_prefix(player, self.PAIR_ACK_PREFIX + request_id + ".")
                if self._is_addon_safe_mumble_name(str(player.name)):
                    self._add_tag(player, f"{self.PAIR_ACK_PREFIX}{request_id}.ok.{player.name}")
                else:
                    self._add_tag(player, f"{self.PAIR_ACK_PREFIX}{request_id}.ok")

            elif tag.startswith(self.PAIR_SYNC_PREFIX):
                request_id = tag[len(self.PAIR_SYNC_PREFIX):]
                self._remove_tag(player, tag)
                if request_id:
                    current = self._mumble_name_for(key, str(player.name))
                    self._clear_tags_with_prefix(player, self.PAIR_ACK_PREFIX + request_id + ".")
                    if self._is_addon_safe_mumble_name(current):
                        self._add_tag(player, f"{self.PAIR_ACK_PREFIX}{request_id}.ok.{current}")
                    else:
                        self._add_tag(player, f"{self.PAIR_ACK_PREFIX}{request_id}.ok")

    @staticmethod
    def _is_addon_safe_mumble_name(value: str) -> bool:
        if not value or len(value) > 64:
            return False
        return all(ch.isascii() and (ch.isalnum() or ch in "_.-") for ch in value)

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
