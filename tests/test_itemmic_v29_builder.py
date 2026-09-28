from __future__ import annotations

import json
import subprocess
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / "scripts" / "build-mic-addon-release.py"


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        subprocess.run(
            ["python3", str(BUILDER), "--output", str(out)],
            cwd=ROOT,
            check=True,
        )

        addon = out / "VC_Mumble_ItemMic_v2.10.0.mcaddon"
        assert addon.is_file()

        with zipfile.ZipFile(addon) as outer:
            bp_name = "VC_Mumble_ItemMic_BP_v2.10.0.mcpack"
            rp_name = "VC_Mumble_ItemMic_RP_v2.10.0.mcpack"
            assert set(outer.namelist()) == {bp_name, rp_name}

            bp_file = out / bp_name
            rp_file = out / rp_name
            bp_file.write_bytes(outer.read(bp_name))
            rp_file.write_bytes(outer.read(rp_name))

        with zipfile.ZipFile(bp_file) as bp:
            manifest = json.loads(bp.read("manifest.json"))
            script = bp.read("scripts/main.js").decode("utf-8")

            assert manifest["header"]["version"] == [2, 10, 0]
            assert manifest["header"]["name"] == "VC Mumble Item Mic BP v2.10.0"
            assert "showVoiceRangePreview(player, sliderValue);" in script
            assert "MolangVariableMap" in script
            assert 'variables.setFloat("variable.vcmumble_diameter", radius * 2);' in script
            assert "center.y + 0.04" in script
            assert "center.y + radius" in script
            assert '.button("20 บล็อก", () => submitQuickRange(20), {' in script
            assert '.button("5 บล็อก"' not in script
            assert "spawnVoiceRangePreviewPoint(player" not in script
            assert "VOICE_RANGE_COMMIT_DEBOUNCE_TICKS = 8" in script
            assert "VOICE_RANGE_CHANGE_COOLDOWN_TICKS = 20 * 30" in script
            assert "startVoiceRangeCooldown(player);" in script
            assert "queuedSliderRange = sliderValue;" in script
            assert "const valueToCommit = queuedSliderRange;" in script
            assert "showVoiceRangePreview(player, sliderValue);\n          submitRange(sliderValue);" not in script
            assert "rangeSlider.getData()" in script
            assert script.count("player.spawnParticle(") == 2
            assert "Dimension.spawnParticle" not in script
            assert ".dimension.spawnParticle" not in script
            assert "ใช้ระยะจาก Slider" not in script
            assert "ลากเพื่อ Preview แบบ realtime" in script
            assert 'const mainPageVisible = new ObservableBoolean(true);' in script
            assert '.button("ตั้งค่า", showSettingsPage, {' in script
            assert '.button("กลับหน้าหลัก", showMainPage, {' in script
            assert '.label(statusText)' not in script
            assert '.label(offhandText)' not in script
            assert '.label(rangeConfirmText)' not in script
            assert "กำหนดระยะเอง" not in script
            assert "ใช้ระยะที่กำหนด" not in script
            assert '.header("Distance Volume")' not in script
            assert '.button("4 • แรงมาก"' not in script
            assert "Distance Volume = ปกติ (2)" not in script

        with zipfile.ZipFile(rp_file) as rp:
            manifest = json.loads(rp.read("manifest.json"))
            particle = json.loads(
                rp.read("particles/voice_range_preview.particle.json")
            )
            texture = rp.read("textures/particle/vcmumble_voice_range_dot.png")

            assert manifest["header"]["version"] == [2, 10, 0]
            assert manifest["header"]["name"] == "VC Mumble Mic Icons RP v2.10.0"
            assert (
                particle["particle_effect"]["description"]["identifier"]
                == "vcmumble:voice_range_preview"
            )
            components = particle["particle_effect"]["components"]
            lifetime = components[
                "minecraft:particle_lifetime_expression"
            ]["max_lifetime"]
            assert lifetime <= 0.35
            assert components["minecraft:emitter_rate_instant"]["num_particles"] == 1
            point = components["minecraft:emitter_shape_point"]
            assert point["offset"] == [0, 0, 0]
            assert point["direction"] == [0, 0, 0]
            assert "minecraft:emitter_shape_sphere" not in components
            billboard = components["minecraft:particle_appearance_billboard"]
            assert billboard["facing_camera_mode"] == "emitter_transform_xz"
            assert billboard["size"] == [
                "variable.vcmumble_diameter",
                "variable.vcmumble_diameter",
            ]
            assert "minecraft:particle_motion_dynamic" not in components
            assert texture.startswith(b"\x89PNG\r\n\x1a\n")

    print("Item Mic v2.10.0 two-plate horizontal Voice Range preview + split DDUI: OK")


if __name__ == "__main__":
    main()
