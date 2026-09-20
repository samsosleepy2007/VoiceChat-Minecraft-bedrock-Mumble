from __future__ import annotations

import importlib.metadata

from endstone.command import CommandSender
from endstone.event import PlayerJoinEvent, PlayerQuitEvent
from endstone.plugin import Plugin

from endstone_vc_mumble import VCMumblePlugin


assert issubclass(VCMumblePlugin, Plugin)
assert VCMumblePlugin.api_version == "0.11"
assert VCMumblePlugin.version == "0.3.0"

assert hasattr(CommandSender, "has_permission")
assert callable(getattr(Plugin, "reload_config"))
assert PlayerJoinEvent is not None
assert PlayerQuitEvent is not None

assert "vcmumble" in VCMumblePlugin.commands
assert "vcmumbleadmin" in VCMumblePlugin.commands
assert VCMumblePlugin.permissions["vc_mumble.command.user"]["default"] is True
assert VCMumblePlugin.permissions["vc_mumble.command.admin"]["default"] == "op"

entry_points = importlib.metadata.entry_points(group="endstone")
matches = [ep for ep in entry_points if ep.name == "vc-mumble"]
assert len(matches) == 1, matches
assert matches[0].value == "endstone_vc_mumble:VCMumblePlugin"
assert matches[0].load() is VCMumblePlugin

print("VC Mumble Endstone runtime API smoke test: OK")
