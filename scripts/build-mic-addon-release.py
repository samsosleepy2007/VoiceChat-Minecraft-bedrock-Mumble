#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "minecraft-addon" / "v2.8.0" / "VC_Mumble_ItemMic_v2.8.0.mcaddon.b64"
ADDON_NAME = "VC_Mumble_ItemMic_v2.9.0.mcaddon"
BP_NAME = "VC_Mumble_ItemMic_BP_v2.9.0.mcpack"
RP_NAME = "VC_Mumble_ItemMic_RP_v2.9.0.mcpack"
BASE_BP_NAME = "VC_Mumble_ItemMic_BP_v2.8.0.mcpack"
BASE_RP_NAME = "VC_Mumble_ItemMic_RP_v2.8.0.mcpack"
BASE_SHA256 = "2e5da0b7692383af9b836544e3324bb46cde6185c3419e0ad86de7d850aab522"
VERSION = [2, 9, 0]
BP_UUID = "b6411120-cc4e-44a9-b28d-f43b10cafd86"
RP_UUID = "cb345edb-6e6c-49ac-9950-e2ae07bda214"

PREVIEW_DOT_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAtElEQVR42q2Tuw2DMABEWcNr0KRgBJfusgALuGIAKjpXVCzgjg0oKSN6ZsgEyVl6UCCkKLKL15zvzv/q8fpUOdyJRjTCCgcWzfwqqAm0wosOPJrDc1uQBp6YexHECAHN46mvBYb2ZBjEJKKYIaINeNyxnaOgYYk9xhRaxAoL2oSnJXMWWJoDs6XAJnbY0CIeT+YscBzWyEwrwTfsaDOejky5guwtZB9i9jVmP6QiT7nIZ/qbL6Z1YG+cTpX/AAAAAElFTkSuQmCC"
)

PREVIEW_PARTICLE = {
    "format_version": "1.10.0",
    "particle_effect": {
        "description": {
            "identifier": "vcmumble:voice_range_preview",
            "basic_render_parameters": {
                "material": "particles_alpha",
                "texture": "textures/particle/vcmumble_voice_range_dot",
            },
        },
        "components": {
            "minecraft:emitter_rate_instant": {"num_particles": 1},
            "minecraft:emitter_lifetime_once": {"active_time": 0.01},
            "minecraft:emitter_shape_point": {},
            "minecraft:particle_lifetime_expression": {"max_lifetime": 0.30},
            "minecraft:particle_motion_dynamic": {},
            "minecraft:particle_appearance_billboard": {
                "size": [0.20, 0.20],
                "facing_camera_mode": "lookat_xyz",
                "uv": {
                    "texture_width": 16,
                    "texture_height": 16,
                    "uv": [0, 0],
                    "uv_size": [16, 16],
                },
            },
        },
    },
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def zip_bytes(files: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(files):
            archive.writestr(name, files[name])
    return output.getvalue()


def read_zip(data: bytes) -> dict[str, bytes]:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return {name: archive.read(name) for name in archive.namelist() if not name.endswith("/")}


def bump_manifest(raw: bytes, *, pack: str) -> bytes:
    manifest = json.loads(raw.decode("utf-8"))
    manifest["header"]["version"] = VERSION
    for module in manifest.get("modules", []):
        module["version"] = VERSION

    if pack == "bp":
        manifest["header"]["name"] = "VC Mumble Item Mic BP v2.9.0"
        manifest["header"]["description"] = (
            "VC Mumble Item Mic: Mic ON/OFF, realtime DDUI voice-range preview, "
            "Endstone voice-range control, and distance-volume attenuation control."
        )
        for dependency in manifest.get("dependencies", []):
            if dependency.get("uuid") == RP_UUID:
                dependency["version"] = VERSION
    else:
        manifest["header"]["name"] = "VC Mumble Mic Icons RP v2.9.0"
        manifest["header"]["description"] = (
            "Inventory icons, invisible held Mic model, and local Voice Range preview "
            "particle for VC Mumble Item Mic v2.9.0."
        )

    return (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def patch_main_js(raw: bytes) -> bytes:
    text = raw.decode("utf-8")

    text = text.replace(
        'const DEFAULT_MAX_RANGE = 150;\n',
        'const DEFAULT_MAX_RANGE = 150;\n'
        'const VOICE_RANGE_PREVIEW_PARTICLE = "vcmumble:voice_range_preview";\n'
        'const VOICE_RANGE_PREVIEW_MIN_POINTS = 24;\n'
        'const VOICE_RANGE_PREVIEW_MAX_POINTS = 120;\n',
        1,
    )

    anchor = '''const openSettingsPlayers = new Set();
const openSettingsForms = new Map();

async function showSettings(player) {
'''
    preview = '''function showVoiceRangePreview(player, rawRadius) {
  const radius = Math.max(1, Math.floor(Number(rawRadius) || 1));
  const circumference = Math.PI * 2 * radius;
  const points = Math.max(
    VOICE_RANGE_PREVIEW_MIN_POINTS,
    Math.min(
      VOICE_RANGE_PREVIEW_MAX_POINTS,
      Math.ceil(circumference / 3.5)
    )
  );

  let center;
  try {
    center = player.location;
  } catch {
    return;
  }

  const y = center.y + 0.12;
  for (let i = 0; i < points; i++) {
    const angle = (Math.PI * 2 * i) / points;
    const location = {
      x: center.x + Math.cos(angle) * radius,
      y,
      z: center.z + Math.sin(angle) * radius,
    };

    try {
      // Player.spawnParticle is intentionally used here instead of
      // Dimension.spawnParticle: only this player can see their preview ring.
      player.spawnParticle(VOICE_RANGE_PREVIEW_PARTICLE, location);
    } catch {
      // A very large range can reach unloaded chunks. Skip those points only.
    }
  }
}

const openSettingsPlayers = new Set();
const openSettingsForms = new Map();

async function showSettings(player) {
'''
    if anchor not in text:
        raise RuntimeError("could not locate DDUI settings anchor")
    text = text.replace(anchor, preview, 1)

    old_slider_state = '''    const customRange = new ObservableString(String(initialRange), {
      clientWritable: true,
    });

    let confirmedRange = initialRange;
'''
    new_slider_state = '''    const customRange = new ObservableString(String(initialRange), {
      clientWritable: true,
    });
    let lastSliderRange = Math.floor(rangeSlider.getData());

    let confirmedRange = initialRange;
'''
    if old_slider_state not in text:
        raise RuntimeError("could not locate range slider state")
    text = text.replace(old_slider_state, new_slider_state, 1)

    old_submit = '''      const maxNow = sliderMax.getData();
      if (value >= 1 && value <= maxNow) rangeSlider.setData(value);

      rangeConfirmText.setData(
'''
    new_submit = '''      const maxNow = sliderMax.getData();
      if (value >= 1 && value <= maxNow) {
        lastSliderRange = value;
        rangeSlider.setData(value);
      }

      rangeConfirmText.setData(
'''
    if old_submit not in text:
        raise RuntimeError("could not locate range submit slider sync")
    text = text.replace(old_submit, new_submit, 1)

    old_slider_ui = '''      .slider("ระยะเสียงแบบ Slider", rangeSlider, 1, sliderMax, {
        step: 1,
        description:
          "ระยะนี้เป็นระยะของผู้พูดและ Endstone จะส่งต่อไปยัง Mumble proximity routing",
      })
      .button("ใช้ระยะจาก Slider", () => submitRange(rangeSlider.getData()))
      .spacer()
'''
    new_slider_ui = '''      .slider("ระยะเสียงแบบ Slider", rangeSlider, 1, sliderMax, {
        step: 1,
        description:
          "ลากเพื่อเปลี่ยนระยะทันที • วง Preview จะเห็นเฉพาะตัวคุณเอง",
      })
      .spacer()
'''
    if old_slider_ui not in text:
        raise RuntimeError("could not locate DDUI slider controls")
    text = text.replace(old_slider_ui, new_slider_ui, 1)

    old_reset = '''        customRange.setData(String(resetRange));
        rangeSlider.setData(Math.min(resetRange, sliderMax.getData()));
        applyMicModeFromUi(
'''
    new_reset = '''        customRange.setData(String(resetRange));
        lastSliderRange = Math.min(resetRange, sliderMax.getData());
        rangeSlider.setData(lastSliderRange);
        applyMicModeFromUi(
'''
    if old_reset not in text:
        raise RuntimeError("could not locate reset slider update")
    text = text.replace(old_reset, new_reset, 1)

    old_refresh = '''        sliderMax.setData(nextMax);
        if (rangeSlider.getData() > nextMax && !isOperator(player)) {
          rangeSlider.setData(nextMax);
        }

        if (pendingRequestId) {
'''
    new_refresh = '''        sliderMax.setData(nextMax);
        if (rangeSlider.getData() > nextMax && !isOperator(player)) {
          lastSliderRange = nextMax;
          rangeSlider.setData(nextMax);
        }

        const sliderValue = Math.max(
          1,
          Math.min(Math.floor(rangeSlider.getData()), nextMax)
        );
        if (sliderValue !== lastSliderRange) {
          // DDUI ObservableNumber is client-writable, so this observes the live
          // slider position without requiring an Apply button.
          lastSliderRange = sliderValue;
          showVoiceRangePreview(player, sliderValue);
          submitRange(sliderValue);
        }

        if (pendingRequestId) {
'''
    if old_refresh not in text:
        raise RuntimeError("could not locate DDUI refresh slider block")
    text = text.replace(old_refresh, new_refresh, 1)

    text = text.replace(
        '''              customRange.setData(String(confirmedRange));
              if (confirmedRange <= nextMax) rangeSlider.setData(confirmedRange);
''',
        '''              customRange.setData(String(confirmedRange));
              if (confirmedRange <= nextMax) {
                lastSliderRange = confirmedRange;
                rangeSlider.setData(confirmedRange);
              }
''',
        1,
    )
    text = text.replace(
        '''                customRange.setData(String(confirmedRange));
                if (confirmedRange <= nextMax) rangeSlider.setData(confirmedRange);
''',
        '''                customRange.setData(String(confirmedRange));
                if (confirmedRange <= nextMax) {
                  lastSliderRange = confirmedRange;
                  rangeSlider.setData(confirmedRange);
                }
''',
        1,
    )

    text = text.replace(
        '"[VCMumbleItem/BP] Loaded v2.8.0 — VC Mumble native mic/range contract (feature/minecraft-mic-addon-v1)"',
        '"[VCMumbleItem/BP] Loaded v2.9.0 — realtime local Voice Range preview + VC Mumble mic/range contract"',
        1,
    )

    required = [
        "showVoiceRangePreview(player, sliderValue);",
        "submitRange(sliderValue);",
        "player.spawnParticle(VOICE_RANGE_PREVIEW_PARTICLE, location);",
        "only this player can see their preview ring",
    ]
    for marker in required:
        if marker not in text:
            raise RuntimeError(f"missing patched marker: {marker}")
    if "ใช้ระยะจาก Slider" in text:
        raise RuntimeError("legacy slider Apply button still exists")

    return text.encode("utf-8")


def build_packs(base_addon: bytes) -> tuple[bytes, bytes]:
    outer = read_zip(base_addon)
    if BASE_BP_NAME not in outer or BASE_RP_NAME not in outer:
        raise RuntimeError("v2.8.0 base addon is missing BP/RP")

    bp = read_zip(outer[BASE_BP_NAME])
    rp = read_zip(outer[BASE_RP_NAME])

    bp["manifest.json"] = bump_manifest(bp["manifest.json"], pack="bp")
    bp["scripts/main.js"] = patch_main_js(bp["scripts/main.js"])

    rp["manifest.json"] = bump_manifest(rp["manifest.json"], pack="rp")
    rp["particles/voice_range_preview.particle.json"] = (
        json.dumps(PREVIEW_PARTICLE, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")
    rp["textures/particle/vcmumble_voice_range_dot.png"] = PREVIEW_DOT_PNG

    return zip_bytes(bp), zip_bytes(rp)


def validate_pack(pack_bytes: bytes, expected_uuid: str, expected_name: str) -> None:
    with zipfile.ZipFile(io.BytesIO(pack_bytes)) as archive:
        manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
        assert manifest["header"]["uuid"] == expected_uuid
        assert manifest["header"]["version"] == VERSION
        assert manifest["header"]["name"] == expected_name


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    base = base64.b64decode(SOURCE.read_text(encoding="ascii"))
    actual_base = sha256(base)
    if actual_base != BASE_SHA256:
        raise RuntimeError(f"Item Mic v2.8.0 base checksum mismatch: {actual_base}")

    bp, rp = build_packs(base)

    addon = output / ADDON_NAME
    with zipfile.ZipFile(addon, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as outer:
        outer.writestr(BP_NAME, bp)
        outer.writestr(RP_NAME, rp)

    validate_pack(bp, BP_UUID, "VC Mumble Item Mic BP v2.9.0")
    validate_pack(rp, RP_UUID, "VC Mumble Mic Icons RP v2.9.0")

    (output / BP_NAME).write_bytes(bp)
    (output / RP_NAME).write_bytes(rp)

    with zipfile.ZipFile(io.BytesIO(rp)) as archive:
        assert "particles/voice_range_preview.particle.json" in archive.namelist()
        assert "textures/particle/vcmumble_voice_range_dot.png" in archive.namelist()

    print(addon)
    print(f"sha256={sha256(addon.read_bytes())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
