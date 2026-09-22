from __future__ import annotations

import json
import math
import secrets
import time
from typing import Any

from endstone import Player
from endstone.command import Command, CommandSender
from endstone.form import ActionForm, MessageForm, ModalForm, Slider, TextInput
from endstone.plugin import Plugin

from .bridge import BridgeServer
from .listener import VCMumbleListener
from .model import PlayerState


ATTENUATION_LEVELS: dict[int, tuple[str, str]] = {
    0: ("ปิด", "เสียงเต็ม 100% จนถึงขอบระยะ แล้วตัดเสียง"),
    1: ("เบา", "ลดเสียงแบบนุ่ม เหมาะกับการคุยทั่วไป"),
    2: ("ปกติ", "สมดุลระหว่างความชัดและความรู้สึกของระยะ"),
    3: ("แรง", "ผู้เล่นที่อยู่ไกลจะได้ยินเบาลงชัดเจน"),
    4: ("แรงมาก", "ปลายระยะจะเบามาก เหมาะกับ proximity เข้ม"),
}


class VCMumblePlugin(Plugin):
    prefix = "VCMumble"
    version = "0.4.1"
    api_version = "0.11"
    description = "Standalone Minecraft position bridge for VC Mumble Server"
    authors = ["SamSoSleepy"]

    commands = {
        "vcb": {
            "description": "Open the VC Mumble Bridge control panel",
            "usages": ["/vcb"],
            "permissions": ["vc_mumble.command.user"],
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
        self._default_attenuation_level = 2
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
            f"default_range={self._default_range} max_range={self._max_range} "
            f"default_attenuation={self._default_attenuation_level}"
        )
        self.logger.info("Command: /vcb opens the VC Mumble Bridge UI")

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
        self._default_attenuation_level = self._bounded_int(voice.get("default_attenuation_level", 2), 0, 4, 2)

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
        if command.name != "vcb":
            return False
        if not isinstance(sender, Player):
            return self._command_status(sender)
        self._show_main_menu(sender)
        return True

    def _attenuation_level_for_key(self, key: str) -> int:
        binding = self._bindings.get(key, {})
        return self._bounded_int(binding.get("attenuation_level", self._default_attenuation_level), 0, 4, self._default_attenuation_level)

    def _attenuation_label(self, level: int) -> str:
        return ATTENUATION_LEVELS.get(level, ATTENUATION_LEVELS[self._default_attenuation_level])[0]

    def _bridge_state_label(self) -> str:
        bridge = self._bridge
        if bridge is None:
            return "offline"
        if bridge.client_connected:
            return "connected"
        if bridge.listening:
            return "listening"
        return "offline"

    def _show_main_menu(self, player: Player) -> None:
        key = self._player_key(player)
        binding = self._bindings.get(key, {})
        voice_range = int(binding.get("range") or self._default_range)
        attenuation_level = self._attenuation_level_for_key(key)
        mumble_name = str(binding.get("mumble_name") or player.name)

        form = ActionForm(
            title="VC Mumble Bridge",
            content=(
                f"Bridge: {self._bridge_state_label()}\n"
                f"Voice Range: {voice_range} blocks\n"
                f"Distance Volume: {self._attenuation_label(attenuation_level)}\n"
                f"Mumble: {mumble_name}"
            ),
        )
        form.add_button("ระยะเสียง", on_click=self._show_range_form)
        form.add_button("ระดับเสียงตามระยะ", on_click=self._show_attenuation_menu)
        form.add_button("จับคู่ชื่อ Mumble", on_click=self._show_pair_form)
        form.add_button("ยกเลิกการจับคู่", on_click=self._show_unpair_confirm)
        form.add_button("ซิงก์ข้อมูลของฉัน", on_click=self._sync_player_from_ui)
        if player.has_permission("vc_mumble.command.admin"):
            form.add_button("เครื่องมือผู้ดูแล", on_click=self._show_admin_menu)
        player.send_form(form)

    def _show_range_form(self, player: Player) -> None:
        key = self._player_key(player)
        current = int(self._bindings.get(key, {}).get("range") or self._default_range)
        form = ModalForm(
            title="VC Mumble • Voice Range",
            controls=[
                Slider(
                    label=f"ระยะเสียง 1-{self._max_range} บล็อก",
                    min=1,
                    max=self._max_range,
                    step=1,
                    default_value=current,
                )
            ],
            on_submit=self._submit_range_form,
        )
        player.send_form(form)

    def _submit_range_form(self, player: Player, data: str) -> None:
        try:
            values = json.loads(data)
            value = int(round(float(values[0])))
        except (ValueError, TypeError, IndexError, json.JSONDecodeError):
            player.send_error_message("ไม่สามารถอ่านค่า Voice Range ได้")
            return
        self._command_range(player, value)
        self._show_main_menu(player)

    def _show_attenuation_menu(self, player: Player) -> None:
        current = self._attenuation_level_for_key(self._player_key(player))
        form = ActionForm(
            title="VC Mumble • Distance Volume",
            content=(
                "เลือกระดับการลดความดังเมื่อผู้พูดอยู่ไกลขึ้น\n"
                f"ปัจจุบัน: {self._attenuation_label(current)}"
            ),
        )
        for level, (label, description) in ATTENUATION_LEVELS.items():
            marker = " ✓" if level == current else ""
            form.add_button(
                f"{label}{marker}\n{description}",
                on_click=lambda selected_player, selected_level=level: self._set_player_attenuation(
                    selected_player, selected_level, reopen=True
                ),
            )
        player.send_form(form)

    def _set_player_attenuation(self, player: Player, level: int, reopen: bool = False) -> None:
        level = self._bounded_int(level, 0, 4, self._default_attenuation_level)
        key = self._player_key(player)
        binding = dict(self._bindings.get(key, {}))
        binding["attenuation_level"] = level
        binding.setdefault("range", self._default_range)
        self._bindings[key] = binding
        self._save_bindings()
        self._broadcast_current_player(player)
        player.send_message(
            f"VC Mumble distance volume set to {self._attenuation_label(level)} (level {level})."
        )
        if reopen:
            self._show_main_menu(player)

    def _show_pair_form(self, player: Player) -> None:
        key = self._player_key(player)
        current = str(self._bindings.get(key, {}).get("mumble_name") or player.name)
        form = ModalForm(
            title="VC Mumble • Pair Mumble",
            controls=[
                TextInput(
                    label="Mumble username",
                    placeholder="ชื่อที่ใช้ใน Mumble",
                    default_value=current,
                )
            ],
            on_submit=self._submit_pair_form,
        )
        player.send_form(form)

    def _submit_pair_form(self, player: Player, data: str) -> None:
        try:
            values = json.loads(data)
            value = str(values[0]).strip()
        except (TypeError, IndexError, json.JSONDecodeError):
            player.send_error_message("ไม่สามารถอ่านชื่อ Mumble ได้")
            return
        self._command_pair(player, value)
        self._show_main_menu(player)

    def _show_unpair_confirm(self, player: Player) -> None:
        form = MessageForm(
            title="VC Mumble • Unpair",
            content="ยกเลิกชื่อ Mumble ที่จับคู่ไว้ และกลับไปใช้ชื่อ Minecraft?",
            button1="ยืนยัน",
            button2="ยกเลิก",
            on_submit=lambda selected_player, selected: (
                self._unpair_from_ui(selected_player) if selected == 0 else self._show_main_menu(selected_player)
            ),
        )
        player.send_form(form)

    def _unpair_from_ui(self, player: Player) -> None:
        self._command_unpair(player)
        self._show_main_menu(player)

    def _sync_player_from_ui(self, player: Player) -> None:
        self._publish_addon_range_tags(player)
        self._broadcast_current_player(player)
        player.send_message("VC Mumble: synced your current voice settings.")
        self._show_main_menu(player)

    def _show_admin_menu(self, player: Player) -> None:
        if not player.has_permission("vc_mumble.command.admin"):
            player.send_error_message("You do not have permission to administer VC Mumble.")
            return
        form = ActionForm(
            title="VC Mumble • Admin",
            content=(
                f"Bridge: {self._bridge_state_label()}\n"
                f"Tracked players: {len(self._states)}\n"
                f"Default range: {self._default_range} / max {self._max_range}"
            ),
        )
        form.add_button("สถานะระบบ", on_click=self._admin_status_from_ui)
        form.add_button("จัดการผู้เล่น", on_click=self._show_admin_players)
        form.add_button("Resync ผู้เล่นทั้งหมด", on_click=self._admin_resync_from_ui)
        form.add_button("Reload Config / Bridge", on_click=self._admin_reload_from_ui)
        form.add_button("กลับ", on_click=self._show_main_menu)
        player.send_form(form)

    def _admin_status_from_ui(self, player: Player) -> None:
        self._command_admin(player, ["status"])
        self._show_admin_menu(player)

    def _admin_resync_from_ui(self, player: Player) -> None:
        self._command_admin(player, ["resync"])
        self._show_admin_menu(player)

    def _admin_reload_from_ui(self, player: Player) -> None:
        self._command_admin(player, ["reload"])
        self._show_admin_menu(player)

    def _show_admin_players(self, player: Player) -> None:
        if not player.has_permission("vc_mumble.command.admin"):
            return
        online = list(self.server.online_players)
        form = ActionForm(
            title="VC Mumble • Players",
            content=f"Online: {len(online)}",
        )
        for target in sorted(online, key=lambda item: str(item.name).lower()):
            key = self._player_key(target)
            voice_range = int(self._bindings.get(key, {}).get("range") or self._default_range)
            attenuation = self._attenuation_label(self._attenuation_level_for_key(key))
            form.add_button(
                f"{target.name}\n{voice_range} blocks • {attenuation}",
                on_click=lambda admin, target_name=str(target.name): self._show_admin_player(admin, target_name),
            )
        form.add_button("กลับ", on_click=self._show_admin_menu)
        player.send_form(form)

    def _show_admin_player(self, admin: Player, target_name: str) -> None:
        target = self._find_online_player(target_name)
        if target is None:
            admin.send_error_message(f"Player not found: {target_name}")
            self._show_admin_players(admin)
            return
        key = self._player_key(target)
        voice_range = int(self._bindings.get(key, {}).get("range") or self._default_range)
        attenuation = self._attenuation_label(self._attenuation_level_for_key(key))
        form = ActionForm(
            title=f"VC Mumble • {target.name}",
            content=f"Range: {voice_range} blocks\nDistance Volume: {attenuation}",
        )
        form.add_button(
            "ตั้ง Voice Range",
            on_click=lambda player, name=str(target.name): self._show_admin_range_form(player, name),
        )
        form.add_button(
            "ตั้งระดับเสียงตามระยะ",
            on_click=lambda player, name=str(target.name): self._show_admin_attenuation_menu(player, name),
        )
        form.add_button("กลับ", on_click=self._show_admin_players)
        admin.send_form(form)

    def _show_admin_range_form(self, admin: Player, target_name: str) -> None:
        target = self._find_online_player(target_name)
        if target is None:
            admin.send_error_message(f"Player not found: {target_name}")
            return
        key = self._player_key(target)
        current = int(self._bindings.get(key, {}).get("range") or self._default_range)
        form = ModalForm(
            title=f"Voice Range • {target.name}",
            controls=[
                Slider(
                    label=f"1-{self._max_range} blocks",
                    min=1,
                    max=self._max_range,
                    step=1,
                    default_value=current,
                )
            ],
            on_submit=lambda player, data, name=str(target.name): self._submit_admin_range(player, name, data),
        )
        admin.send_form(form)

    def _submit_admin_range(self, admin: Player, target_name: str, data: str) -> None:
        try:
            values = json.loads(data)
            value = int(round(float(values[0])))
        except (ValueError, TypeError, IndexError, json.JSONDecodeError):
            admin.send_error_message("ไม่สามารถอ่านค่า Voice Range ได้")
            return
        self._command_admin(admin, ["range", target_name, str(value)])
        self._show_admin_player(admin, target_name)

    def _show_admin_attenuation_menu(self, admin: Player, target_name: str) -> None:
        target = self._find_online_player(target_name)
        if target is None:
            admin.send_error_message(f"Player not found: {target_name}")
            return
        current = self._attenuation_level_for_key(self._player_key(target))
        form = ActionForm(
            title=f"Distance Volume • {target.name}",
            content=f"ปัจจุบัน: {self._attenuation_label(current)}",
        )
        for level, (label, description) in ATTENUATION_LEVELS.items():
            marker = " ✓" if level == current else ""
            form.add_button(
                f"{label}{marker}\n{description}",
                on_click=lambda player, selected_level=level, name=str(target.name): self._set_admin_attenuation(
                    player, name, selected_level
                ),
            )
        form.add_button(
            "กลับ",
            on_click=lambda player, name=str(target.name): self._show_admin_player(player, name),
        )
        admin.send_form(form)

    def _set_admin_attenuation(self, admin: Player, target_name: str, level: int) -> None:
        target = self._find_online_player(target_name)
        if target is None:
            admin.send_error_message(f"Player not found: {target_name}")
            return
        self._set_player_attenuation(target, level, reopen=False)
        admin.send_message(
            f"VC Mumble distance volume for {target.name} set to {self._attenuation_label(level)}."
        )
        self._show_admin_player(admin, target_name)

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
            attenuation_level = self._attenuation_level_for_key(key)
            sender.send_message(
                f"Mumble username: {mumble_name} | range: {voice_range} blocks | "
                f"distance volume: {self._attenuation_label(attenuation_level)}"
            )
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
        if old is None or not old.get("mumble_name"):
            player.send_message("VC Mumble was already using your Minecraft name automatically.")
            return True

        binding = dict(old)
        binding.pop("mumble_name", None)

        current_range = int(binding.get("range") or self._default_range)
        if current_range == self._default_range:
            binding.pop("range", None)

        current_attenuation = self._bounded_int(
            binding.get("attenuation_level", self._default_attenuation_level),
            0,
            4,
            self._default_attenuation_level,
        )
        if current_attenuation == self._default_attenuation_level:
            binding.pop("attenuation_level", None)

        if binding:
            self._bindings[key] = binding
        else:
            self._bindings.pop(key, None)

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
                    f"- {state.name} -> {mumble_name} | {voice_range} blocks | "
                    f"{self._attenuation_label(self._attenuation_level_for_key(key))} | {state.dimension}"
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
        self._publish_addon_range_tags(player)
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
            addon_changed = self._process_addon_controls(player)
            state = self._snapshot_if_valid(player)
            if state is None:
                continue
            previous = self._states.get(key)
            if addon_changed or previous is None or state.changed_from(previous, self._position_epsilon, self._rotation_epsilon):
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
            "voiceEnabled": bool(state.voice_enabled),
            "attenuationLevel": self._attenuation_level_for_key(key),
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
                voice_enabled=self._voice_enabled_for(player),
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

    def _process_addon_controls(self, player: Player) -> bool:
        try:
            tags = list(player.scoreboard_tags)
        except Exception:
            return False

        key = self._player_key(player)
        binding = dict(self._bindings.get(key, {}))
        current_range = int(binding.get("range") or self._default_range)
        maximum = 1000 if self._is_operator(player) else self._max_range
        changed = False

        for tag in tags:
            if tag.startswith("vcmumble.vr.sync."):
                request_id = tag[len("vcmumble.vr.sync."):]
                self._remove_player_tag(player, tag)
                self._publish_addon_range_tags(player, current_range, maximum)
                if request_id:
                    self._add_player_tag(player, f"vcmumble.vr.ack.{request_id}.ok.{current_range}")
                continue

            if not tag.startswith("vcmumble.vr.request."):
                continue

            payload = tag[len("vcmumble.vr.request."):]
            request_id, separator, raw_value = payload.rpartition(".")
            self._remove_player_tag(player, tag)
            status = "error"
            accepted = current_range
            try:
                requested = int(raw_value) if separator else 0
            except ValueError:
                requested = 0

            if request_id and 1 <= requested <= maximum:
                accepted = requested
                status = "ok"
                if accepted != current_range:
                    binding["range"] = accepted
                    self._bindings[key] = binding
                    self._save_bindings()
                    current_range = accepted
                    changed = True

            self._publish_addon_range_tags(player, current_range, maximum)
            if request_id:
                self._add_player_tag(player, f"vcmumble.vr.ack.{request_id}.{status}.{current_range}")

        return changed

    def _publish_addon_range_tags(
        self,
        player: Player,
        current_range: int | None = None,
        maximum: int | None = None,
    ) -> None:
        key = self._player_key(player)
        binding = self._bindings.get(key, {})
        value = int(current_range or binding.get("range") or self._default_range)
        max_value = int(maximum or (1000 if self._is_operator(player) else self._max_range))
        self._replace_player_tag_prefix(player, "vcmumble.vr.value.", f"vcmumble.vr.value.{value}")
        self._replace_player_tag_prefix(player, "vcmumble.vr.max.", f"vcmumble.vr.max.{max_value}")

    @staticmethod
    def _replace_player_tag_prefix(player: Player, prefix: str, replacement: str) -> None:
        try:
            for tag in list(player.scoreboard_tags):
                if tag.startswith(prefix) and tag != replacement:
                    player.remove_scoreboard_tag(tag)
            if replacement not in set(player.scoreboard_tags):
                player.add_scoreboard_tag(replacement)
        except Exception:
            pass

    @staticmethod
    def _add_player_tag(player: Player, tag: str) -> None:
        try:
            player.add_scoreboard_tag(tag)
        except Exception:
            pass

    @staticmethod
    def _remove_player_tag(player: Player, tag: str) -> None:
        try:
            player.remove_scoreboard_tag(tag)
        except Exception:
            pass

    @staticmethod
    def _is_operator(player: Player) -> bool:
        try:
            return bool(player.is_op)
        except Exception:
            return False

    @staticmethod
    def _voice_enabled_for(player: Player) -> bool:
        try:
            tags = set(player.scoreboard_tags)
            has_on = "vcmumble.mic.on" in tags
            has_off = "vcmumble.mic.off" in tags

            # ON wins if both tags exist. Older addon builds could leave the
            # previous OFF tag behind even though the Mic item/UI already
            # switched to ON. Heal that conflict server-side immediately.
            if has_on:
                if has_off:
                    try:
                        player.remove_scoreboard_tag("vcmumble.mic.off")
                    except Exception:
                        pass
                return True
            if has_off:
                return False
        except Exception:
            pass
        return True

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
