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

        addon = out / "VC_Mumble_ItemMic_v2.9.0.mcaddon"
        assert addon.is_file()

        with zipfile.ZipFile(addon) as outer:
            bp_name = "VC_Mumble_ItemMic_BP_v2.9.0.mcpack"
            rp_name = "VC_Mumble_ItemMic_RP_v2.9.0.mcpack"
            assert set(outer.namelist()) == {bp_name, rp_name}

            bp_file = out / bp_name
            rp_file = out / rp_name
            bp_file.write_bytes(outer.read(bp_name))
            rp_file.write_bytes(outer.read(rp_name))

        with zipfile.ZipFile(bp_file) as bp:
            manifest = json.loads(bp.read("manifest.json"))
            script = bp.read("scripts/main.js").decode("utf-8")

            assert manifest["header"]["version"] == [2, 9, 0]
            assert manifest["header"]["name"] == "VC Mumble Item Mic BP v2.9.0"
            assert "showVoiceRangePreview(player, sliderValue);" in script
            assert "VOICE_RANGE_COMMIT_DEBOUNCE_TICKS = 8" in script
            assert "queuedSliderRange = sliderValue;" in script
            assert "const valueToCommit = queuedSliderRange;" in script
            assert "showVoiceRangePreview(player, sliderValue);\n          submitRange(sliderValue);" not in script
            assert "rangeSlider.getData()" in script
            assert "player.spawnParticle(VOICE_RANGE_PREVIEW_PARTICLE, location);" in script
            assert "Dimension.spawnParticle" not in script
            assert ".dimension.spawnParticle" not in script
            assert "ใช้ระยะจาก Slider" not in script
            assert "วง Preview จะเห็นเฉพาะตัวคุณเอง" in script

        with zipfile.ZipFile(rp_file) as rp:
            manifest = json.loads(rp.read("manifest.json"))
            particle = json.loads(
                rp.read("particles/voice_range_preview.particle.json")
            )
            texture = rp.read("textures/particle/vcmumble_voice_range_dot.png")

            assert manifest["header"]["version"] == [2, 9, 0]
            assert manifest["header"]["name"] == "VC Mumble Mic Icons RP v2.9.0"
            assert (
                particle["particle_effect"]["description"]["identifier"]
                == "vcmumble:voice_range_preview"
            )
            lifetime = particle["particle_effect"]["components"][
                "minecraft:particle_lifetime_expression"
            ]["max_lifetime"]
            assert lifetime <= 0.35
            assert texture.startswith(b"\x89PNG\r\n\x1a\n")

    print("Item Mic v2.9.0 realtime private Voice Range preview: OK")


if __name__ == "__main__":
    main()
