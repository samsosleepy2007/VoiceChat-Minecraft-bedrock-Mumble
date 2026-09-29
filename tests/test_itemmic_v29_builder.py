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

        addon = out / "VC_Mumble_ItemMic_v2.11.0.mcaddon"
        assert addon.is_file()

        with zipfile.ZipFile(addon) as outer:
            bp_name = "VC_Mumble_ItemMic_BP_v2.11.0.mcpack"
            rp_name = "VC_Mumble_ItemMic_RP_v2.11.0.mcpack"
            assert set(outer.namelist()) == {bp_name, rp_name}

            bp_file = out / bp_name
            rp_file = out / rp_name
            bp_file.write_bytes(outer.read(bp_name))
            rp_file.write_bytes(outer.read(rp_name))

        with zipfile.ZipFile(bp_file) as bp:
            manifest = json.loads(bp.read("manifest.json"))
            script = bp.read("scripts/main.js").decode("utf-8")

            assert manifest["header"]["version"] == [2, 11, 0]
            assert manifest["header"]["name"] == "VC Mumble Item Mic BP v2.11.0"
            assert "showVoiceRangePreview(player, settledValue);" in script
            assert "new MolangVariableMap()" not in script
            assert "variable.vcmumble_diameter" not in script
            assert 'String(radius).padStart(3, "0")' in script
            assert 'const particleId = voiceRangePreviewParticleId(radius);' in script
            assert "center.y + 0.04" in script
            assert "center.y + radius" in script
            assert '.button("20 บล็อก", () => submitQuickRange(20), {' in script
            assert '.button("5 บล็อก"' not in script
            assert "spawnVoiceRangePreviewPoint(player" not in script
            assert "VOICE_RANGE_SLIDER_SETTLE_TICKS = 15" in script
            assert "VOICE_RANGE_CHANGE_COOLDOWN_TICKS = 20 * 30" in script
            assert "startVoiceRangeCooldown(player);" in script
            assert "sliderCandidateRange = sliderValue;" in script
            assert "sliderSettleDueTick" in script
            assert "showVoiceRangePreview(player, settledValue);" in script
            assert "setObservableIfChanged(sliderMax, nextMax);" in script
            assert "const valueToCommit = queuedSliderRange;" in script
            assert "showVoiceRangePreview(player, sliderValue);\n          submitRange(sliderValue);" not in script
            assert "rangeSlider.getData()" in script
            assert script.count("player.spawnParticle(") == 2
            assert "Dimension.spawnParticle" not in script
            assert ".dimension.spawnParticle" not in script
            assert "ใช้ระยะจาก Slider" not in script
            assert "เมื่อหยุดประมาณ 0.75 วิ" in script
            assert 'const mainPageVisible = new ObservableBoolean(true);' in script
            assert "const cooldownStatusText = new ObservableString(" in script
            assert '.label(cooldownStatusText, { visible: mainPageVisible })' in script
            assert "คูลดาวน์เปลี่ยนระยะ" in script
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

            phone_item = json.loads(bp.read("items/phone.item.json"))
            phone_desc = phone_item["minecraft:item"]["description"]
            phone_components = phone_item["minecraft:item"]["components"]
            assert phone_desc["identifier"] == "voicecraft:phone"
            assert phone_desc["menu_category"]["category"] == "equipment"
            assert phone_components["minecraft:display_name"]["value"] == "โทรศัพท์"
            assert (
                phone_components["minecraft:icon"]["textures"]["default"]
                == "voicecraft_phone"
            )
            assert phone_components["minecraft:max_stack_size"] == 1
            assert phone_components["minecraft:allow_off_hand"] is False
            assert "minecraft:interact_button" not in phone_components
            assert "vcmumble:open_settings" not in phone_components

        with zipfile.ZipFile(rp_file) as rp:
            manifest = json.loads(rp.read("manifest.json"))
            particle_1 = json.loads(
                rp.read("particles/voice_range_preview_001.particle.json")
            )
            particle_150 = json.loads(
                rp.read("particles/voice_range_preview_150.particle.json")
            )
            texture = rp.read("textures/particle/vcmumble_voice_range_dot.png")
            phone_attachable = json.loads(rp.read("attachables/phone.entity.json"))
            phone_geometry = json.loads(
                rp.read("models/entity/voicecraft_phone.geo.json")
            )
            phone_atlas = json.loads(rp.read("textures/item_texture.json"))
            phone_texture = rp.read("textures/entity/voicecraft_phone.png")
            phone_icon = rp.read("textures/items/icon_phone.png")

            assert manifest["header"]["version"] == [2, 11, 0]
            assert manifest["header"]["name"] == "VC Mumble Mic Icons RP v2.11.0"
            for radius, particle in ((1, particle_1), (150, particle_150)):
                assert (
                    particle["particle_effect"]["description"]["identifier"]
                    == f"vcmumble:voice_range_preview_{radius:03d}"
                )
                components = particle["particle_effect"]["components"]
                lifetime = components[
                    "minecraft:particle_lifetime_expression"
                ]["max_lifetime"]
                assert lifetime == 1.2
                assert components["minecraft:emitter_rate_instant"]["num_particles"] == 1
                point = components["minecraft:emitter_shape_point"]
                assert point["offset"] == [0, 0, 0]
                assert point["direction"] == [0, 0, 0]
                assert "minecraft:emitter_shape_sphere" not in components
                billboard = components["minecraft:particle_appearance_billboard"]
                assert billboard["facing_camera_mode"] == "emitter_transform_xz"
                assert billboard["size"] == [float(radius * 2), float(radius * 2)]
                assert "minecraft:particle_motion_dynamic" not in components

            particle_files = [
                name for name in rp.namelist()
                if name.startswith("particles/voice_range_preview_")
                and name.endswith(".particle.json")
            ]
            assert len(particle_files) == 150
            assert texture.startswith(b"\x89PNG\r\n\x1a\n")

            attach_desc = phone_attachable["minecraft:attachable"]["description"]
            assert attach_desc["identifier"] == "voicecraft:phone"
            assert attach_desc["geometry"]["default"] == "geometry.voicecraft.phone"
            assert (
                attach_desc["textures"]["default"]
                == "textures/entity/voicecraft_phone"
            )
            geometry = phone_geometry["minecraft:geometry"][0]
            assert phone_geometry["format_version"] == "1.16.0"
            assert geometry["description"]["identifier"] == "geometry.voicecraft.phone"
            assert geometry["description"]["texture_width"] == 64
            assert geometry["description"]["texture_height"] == 64
            assert [bone["name"] for bone in geometry["bones"]] == ["White Phone"]
            phone_bone = geometry["bones"][0]
            assert "parent" not in phone_bone
            assert (
                phone_bone["binding"]
                == "q.item_slot_to_bone_name(context.item_slot)"
            )
            assert len(phone_bone["cubes"]) >= 1
            assert (
                phone_atlas["texture_data"]["voicecraft_phone"]["textures"]
                == "textures/items/icon_phone"
            )
            assert phone_texture.startswith(b"\x89PNG\r\n\x1a\n")
            assert phone_icon.startswith(b"\x89PNG\r\n\x1a\n")

    print("Item Mic v2.11.0 + Phone equipment item: OK")


if __name__ == "__main__":
    main()
