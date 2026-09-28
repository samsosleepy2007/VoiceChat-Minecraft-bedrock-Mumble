#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "minecraft-addon" / "v2.8.0" / "VC_Mumble_ItemMic_v2.8.0.mcaddon.b64"
ADDON_NAME = "VC_Mumble_ItemMic_v2.10.0.mcaddon"
BP_NAME = "VC_Mumble_ItemMic_BP_v2.10.0.mcpack"
RP_NAME = "VC_Mumble_ItemMic_RP_v2.10.0.mcpack"
BASE_BP_NAME = "VC_Mumble_ItemMic_BP_v2.8.0.mcpack"
BASE_RP_NAME = "VC_Mumble_ItemMic_RP_v2.8.0.mcpack"
BASE_SHA256 = "2e5da0b7692383af9b836544e3324bb46cde6185c3419e0ad86de7d850aab522"
VERSION = [2, 10, 0]
BP_UUID = "b6411120-cc4e-44a9-b28d-f43b10cafd86"
RP_UUID = "cb345edb-6e6c-49ac-9950-e2ae07bda214"

PREVIEW_DOT_PNG = base64.b64decode(
    "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"
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
            "minecraft:emitter_shape_point": {
                "offset": [0, 0, 0],
                "direction": [0, 0, 0],
            },
            "minecraft:particle_lifetime_expression": {"max_lifetime": 0.28},
            "minecraft:particle_appearance_billboard": {
                "size": [
                    "variable.vcmumble_diameter",
                    "variable.vcmumble_diameter",
                ],
                "facing_camera_mode": "emitter_transform_xz",
                "uv": {
                    "texture_width": 128,
                    "texture_height": 128,
                    "uv": [0, 0],
                    "uv_size": [128, 128],
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
        manifest["header"]["name"] = "VC Mumble Item Mic BP v2.10.0"
        manifest["header"]["description"] = (
            "VC Mumble Item Mic: Mic ON/OFF, realtime private 3D DDUI voice-range preview, "
            "Endstone voice-range control, and distance-volume attenuation control."
        )
        for dependency in manifest.get("dependencies", []):
            if dependency.get("uuid") == RP_UUID:
                dependency["version"] = VERSION
    else:
        manifest["header"]["name"] = "VC Mumble Mic Icons RP v2.10.0"
        manifest["header"]["description"] = (
            "Inventory icons, invisible held Mic model, and private 3D Voice Range preview "
            "particle for VC Mumble Item Mic v2.10.0."
        )

    return (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def patch_main_js(raw: bytes) -> bytes:
    text = raw.decode("utf-8")

    text = text.replace(
        "  system,\\n",
        "  system,\\n  MolangVariableMap,\\n",
        1,
    )

    text = text.replace(
        'const DEFAULT_MAX_RANGE = 150;\n',
        'const DEFAULT_MAX_RANGE = 150;\n'
        'const VOICE_RANGE_PREVIEW_PARTICLE = "vcmumble:voice_range_preview";\n'
        'const VOICE_RANGE_COMMIT_DEBOUNCE_TICKS = 8;\n'
        'const VOICE_RANGE_CHANGE_COOLDOWN_TICKS = 20 * 30;\n',
        1,
    )

    text = text.replace(
        "const states = new Map();\n",
        """const states = new Map();
const rangeChangeCooldownUntil = new Map();

function voiceRangeCooldownTicks(player) {
  return Math.max(
    0,
    (rangeChangeCooldownUntil.get(player.id) ?? 0) - system.currentTick
  );
}

function voiceRangeCooldownSeconds(player) {
  return Math.ceil(voiceRangeCooldownTicks(player) / 20);
}

function startVoiceRangeCooldown(player) {
  rangeChangeCooldownUntil.set(
    player.id,
    system.currentTick + VOICE_RANGE_CHANGE_COOLDOWN_TICKS
  );
}
""",
        1,
    )

    anchor = '''const openSettingsPlayers = new Set();
const openSettingsForms = new Map();

async function showSettings(player) {
'''
    preview = '''function showVoiceRangePreview(player, rawRadius) {
  const radius = Math.max(1, Math.min(150, Math.floor(Number(rawRadius) || 1)));

  let center;
  try {
    center = player.location;
  } catch {
    return;
  }

  try {
    // Construct at runtime only (not during early execution).
    const variables = new MolangVariableMap();
    variables.setFloat("variable.vcmumble_diameter", radius * 2);

    // Bottom plate stays fixed at the player's feet. It never moves downward
    // when the range increases, so the local player can always see it.
    player.spawnParticle(
      VOICE_RANGE_PREVIEW_PARTICLE,
      { x: center.x, y: center.y + 0.04, z: center.z },
      variables
    );

    // Top plate rises exactly with Voice Range while keeping the same radius.
    player.spawnParticle(
      VOICE_RANGE_PREVIEW_PARTICLE,
      { x: center.x, y: center.y + radius, z: center.z },
      variables
    );
  } catch {
    // Preview failure must never affect the authoritative range flow.
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
    let queuedSliderRange = null;
    let sliderCommitDueTick = 0;

    let confirmedRange = initialRange;
'''
    if old_slider_state not in text:
        raise RuntimeError("could not locate range slider state")
    text = text.replace(old_slider_state, new_slider_state, 1)

    cooldown_submit_anchor = '''      const requestId = requestVoiceRange(player, value);
      if (!requestId) {
'''
    cooldown_submit = '''      const cooldownSeconds = voiceRangeCooldownSeconds(player);
      if (cooldownSeconds > 0) {
        queuedSliderRange = value;
        sliderCommitDueTick = system.currentTick + 20;
        customRange.setData(String(value));
        rangeConfirmText.setData(
          `สถานะ Endstone: §eคูลดาวน์ ${cooldownSeconds} วิ • Preview ${value} บล็อก§r\\n`
        );
        return;
      }

      const requestId = requestVoiceRange(player, value);
      if (!requestId) {
'''
    if cooldown_submit_anchor not in text:
        raise RuntimeError("could not locate range cooldown submit anchor")
    text = text.replace(cooldown_submit_anchor, cooldown_submit, 1)

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
          "ลากเพื่อเปลี่ยนระยะทันที • โดม Preview จะเห็นเฉพาะตัวคุณเอง",
      })
      .spacer()
'''
    if old_slider_ui not in text:
        raise RuntimeError("could not locate DDUI slider controls")
    text = text.replace(old_slider_ui, new_slider_ui, 1)

    quick_helper_anchor = '''    const submitAttenuation = (rawLevel) => {
'''
    quick_helper = '''    const submitQuickRange = (rawValue) => {
      const value = Math.floor(Number(rawValue));
      if (!Number.isFinite(value) || value < 1) return;

      lastSliderRange = value;
      rangeSlider.setData(value);
      showVoiceRangePreview(player, value);

      // Repeated taps on the same quick button must not create duplicate
      // requests. If a request is pending, retain only the newest value.
      if (value === pendingRange) return;
      if (!pendingRequestId && value === confirmedRange) {
        rangeConfirmText.setData(
          `สถานะ Endstone: §aใช้อยู่แล้ว — ${value} บล็อก§r\\n`
        );
        return;
      }
      if (pendingRequestId) {
        queuedSliderRange = value;
        sliderCommitDueTick = system.currentTick;
        return;
      }

      queuedSliderRange = null;
      sliderCommitDueTick = 0;
      submitRange(value);
    };

    const submitAttenuation = (rawLevel) => {
'''
    if quick_helper_anchor not in text:
        raise RuntimeError("could not locate quick-range helper anchor")
    text = text.replace(quick_helper_anchor, quick_helper, 1)

    old_quick_buttons = '''      .button("5 บล็อก", () => submitRange(5))
      .button("10 บล็อก", () => submitRange(10))
      .button("30 บล็อก", () => submitRange(30))
'''
    new_quick_buttons = '''      .button("10 บล็อก", () => submitQuickRange(10))
      .button("20 บล็อก", () => submitQuickRange(20))
      .button("30 บล็อก", () => submitQuickRange(30))
'''
    if old_quick_buttons not in text:
        raise RuntimeError("could not locate quick-range buttons")
    text = text.replace(old_quick_buttons, new_quick_buttons, 1)

    advanced_ui = '''      .toggle("กำหนดระยะเอง", advancedVisible, {
        description: "เปิดเพื่อกรอกค่าระยะเป็นบล็อก",
      })
      .textField("ระยะเสียง (บล็อก)", customRange, {
        visible: advancedVisible,
        description: isOperator(player)
          ? "Operator ใช้ค่าได้สูงสุดตาม Endstone (ปัจจุบัน 1000)"
          : "ค่าต้องไม่เกินระยะสูงสุดของเซิร์ฟเวอร์",
      })
      .button("ใช้ระยะที่กำหนด", () => submitRange(customRange.getData()), {
        visible: advancedVisible,
      })
      .spacer()
      .divider()
'''
    if advanced_ui not in text:
        raise RuntimeError("could not locate advanced range UI")
    text = text.replace(advanced_ui, '''      .divider()
''', 1)

    attenuation_ui = '''      .header("Distance Volume")
      .label(attenuationText)
      .label(attenuationConfirmText)
      .label(
        "VC Mumla จะค่อย ๆ ลดเสียงตามระยะของผู้พูด\\n" +
        "0 = เสียงเต็มจนสุดระยะ • 4 = เบามากเมื่อใกล้ขอบวง\\n"
      )
      .button("0 • ปิดการลดเสียง", () => submitAttenuation(0))
      .button("1 • เบา", () => submitAttenuation(1))
      .button("2 • ปกติ", () => submitAttenuation(2))
      .button("3 • แรง", () => submitAttenuation(3))
      .button("4 • แรงมาก", () => submitAttenuation(4))
      .spacer()
      .divider()
'''
    if attenuation_ui not in text:
        raise RuntimeError("could not locate Distance Volume UI")
    text = text.replace(attenuation_ui, "", 1)

    reset_label = '''      .label("คืน Mic Mode เป็น Hold-to-Talk\\nVoice Range = 30 บล็อก\\nDistance Volume = ปกติ (2)\\n")
'''
    if reset_label not in text:
        raise RuntimeError("could not locate reset label")
    text = text.replace(
        reset_label,
        '''      .label("คืน Mic Mode เป็น Hold-to-Talk\\nVoice Range = 30 บล็อก\\n")
''',
        1,
    )
    text = text.replace("        advancedVisible.setData(false);\\n", "", 1)
    text = text.replace("        submitAttenuation(2);\\n", "", 1)

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
          // Preview stays realtime, but the authoritative Endstone update is
          // debounced so dragging cannot create a request/ACK + disk-write storm.
          lastSliderRange = sliderValue;
          showVoiceRangePreview(player, sliderValue);

          if (!pendingRequestId && sliderValue === confirmedRange) {
            queuedSliderRange = null;
            rangeConfirmText.setData(
              `สถานะ Endstone: §aใช้อยู่แล้ว — ${confirmedRange} บล็อก§r\\n`
            );
          } else {
            queuedSliderRange = sliderValue;
            sliderCommitDueTick =
              system.currentTick + VOICE_RANGE_COMMIT_DEBOUNCE_TICKS;
          }
        }

        if (
          queuedSliderRange !== null &&
          !pendingRequestId &&
          system.currentTick >= sliderCommitDueTick
        ) {
          const valueToCommit = queuedSliderRange;
          queuedSliderRange = null;
          submitRange(valueToCommit);
        }

        if (pendingRequestId) {
'''
    if old_refresh not in text:
        raise RuntimeError("could not locate DDUI refresh slider block")
    text = text.replace(old_refresh, new_refresh, 1)

    cooldown_ack_old = '''            if (implicitAck || (ack?.status === "ok" && acceptedValue === pendingRange)) {
              rangeConfirmText.setData(
                `สถานะ Endstone: §aยืนยันแล้ว — ${acceptedValue} บล็อก${implicitAck ? " (server sync)" : ""}§r\\n`
              );
'''
    cooldown_ack_new = '''            if (implicitAck || (ack?.status === "ok" && acceptedValue === pendingRange)) {
              startVoiceRangeCooldown(player);
              rangeConfirmText.setData(
                `สถานะ Endstone: §aยืนยันแล้ว — ${acceptedValue} บล็อก • คูลดาวน์ 30 วิ${implicitAck ? " (server sync)" : ""}§r\\n`
              );
'''
    if cooldown_ack_old not in text:
        raise RuntimeError("could not locate successful range ACK block")
    text = text.replace(cooldown_ack_old, cooldown_ack_new, 1)

    text = text.replace(
        '''              customRange.setData(String(confirmedRange));
              if (confirmedRange <= nextMax) rangeSlider.setData(confirmedRange);
''',
        '''              customRange.setData(String(confirmedRange));
              if (
                queuedSliderRange === null &&
                confirmedRange <= nextMax
              ) {
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
                if (
                  queuedSliderRange === null &&
                  confirmedRange <= nextMax
                ) {
                  lastSliderRange = confirmedRange;
                  rangeSlider.setData(confirmedRange);
                }
''',
        1,
    )

    main_ui_pattern = re.compile(
        r'    const form = new CustomForm\(player, "VC Mumble • Mic Settings"\).*?      \.closeButton\(\);\n',
        re.DOTALL,
    )
    main_ui_new = '''    const mainPageVisible = new ObservableBoolean(true);
    const settingsPageVisible = new ObservableBoolean(false);
    const showMainPage = () => {
      mainPageVisible.setData(true);
      settingsPageVisible.setData(false);
    };
    const showSettingsPage = () => {
      mainPageVisible.setData(false);
      settingsPageVisible.setData(true);
    };

    const form = new CustomForm(player, "VC Mumble • Mic Settings")
      .header("สถานะ", { visible: mainPageVisible })
      .label(modeText, { visible: mainPageVisible })
      .label(rangeText, { visible: mainPageVisible })
      .label(serverLimitText, { visible: mainPageVisible })
      .spacer({ visible: mainPageVisible })
      .divider({ visible: mainPageVisible })
      .header("Voice Range", { visible: mainPageVisible })
      .button("10 บล็อก", () => submitQuickRange(10), {
        visible: mainPageVisible,
      })
      .button("20 บล็อก", () => submitQuickRange(20), {
        visible: mainPageVisible,
      })
      .button("30 บล็อก", () => submitQuickRange(30), {
        visible: mainPageVisible,
      })
      .spacer({ visible: mainPageVisible })
      .slider("ระยะเสียงแบบ Slider", rangeSlider, 1, sliderMax, {
        step: 1,
        visible: mainPageVisible,
        description:
          "ลากเพื่อ Preview แบบ realtime • การเปลี่ยนระยะจริงมีคูลดาวน์ 30 วิ",
      })
      .spacer({ visible: mainPageVisible })
      .button("ตั้งค่า", showSettingsPage, {
        visible: mainPageVisible,
      })
      .divider({ visible: settingsPageVisible })
      .header("ตั้งค่า", { visible: settingsPageVisible })
      .header("Mic Mode", { visible: settingsPageVisible })
      .label("Hold-to-Talk\\nถือ Mic = เปิดเสียง\\nเลิกถือ = ปิดเสียง\\n", {
        visible: settingsPageVisible,
      })
      .button(
        "Hold-to-Talk",
        () =>
          applyMicModeFromUi(
            player,
            MODE_HOLD,
            statusText,
            modeText,
            holdDisabled,
            toggleDisabled
          ),
        { disabled: holdDisabled, visible: settingsPageVisible }
      )
      .spacer({ visible: settingsPageVisible })
      .label("Toggle\\nหยิบ Mic ขึ้นมาหนึ่งครั้งเพื่อสลับ ON/OFF\\n", {
        visible: settingsPageVisible,
      })
      .button(
        "Toggle",
        () =>
          applyMicModeFromUi(
            player,
            MODE_TOGGLE,
            statusText,
            modeText,
            holdDisabled,
            toggleDisabled
          ),
        { disabled: toggleDisabled, visible: settingsPageVisible }
      )
      .spacer({ visible: settingsPageVisible })
      .divider({ visible: settingsPageVisible })
      .header("Reset", { visible: settingsPageVisible })
      .label("คืน Mic Mode เป็น Hold-to-Talk\\nVoice Range = 30 บล็อก\\n", {
        visible: settingsPageVisible,
      })
      .button("คืนค่าเริ่มต้น", () => {
        const resetRange = isOperator(player) ? 30 : Math.min(30, sliderMax.getData());
        customRange.setData(String(resetRange));
        lastSliderRange = Math.min(resetRange, sliderMax.getData());
        rangeSlider.setData(lastSliderRange);
        applyMicModeFromUi(
          player,
          MODE_HOLD,
          statusText,
          modeText,
          holdDisabled,
          toggleDisabled
        );
        submitRange(resetRange);
      }, { visible: settingsPageVisible })
      .spacer({ visible: settingsPageVisible })
      .button("กลับหน้าหลัก", showMainPage, {
        visible: settingsPageVisible,
      })
      .closeButton();
'''
    text, main_ui_count = main_ui_pattern.subn(
        lambda _: main_ui_new,
        text,
        count=1,
    )
    if main_ui_count != 1:
        raise RuntimeError("could not locate DDUI form layout")


    text = text.replace(
        '"[VCMumbleItem/BP] Loaded v2.8.0 — VC Mumble native mic/range contract (feature/minecraft-mic-addon-v1)"',
        '"[VCMumbleItem/BP] Loaded v2.10.0 — two horizontal green range plates + 30s range cooldown"',
        1,
    )

    required = [
        "showVoiceRangePreview(player, sliderValue);",
        "submitQuickRange(20)",
        'variables.setFloat("variable.vcmumble_diameter", radius * 2);',
        "center.y + 0.04",
        "center.y + radius",
        "MolangVariableMap",
        "const VOICE_RANGE_CHANGE_COOLDOWN_TICKS = 20 * 30;",
        "startVoiceRangeCooldown(player);",
        'button("20 บล็อก", () => submitQuickRange(20), {',
        '.button("ตั้งค่า", showSettingsPage, {',
        '.button("กลับหน้าหลัก", showMainPage, {',
        'const mainPageVisible = new ObservableBoolean(true);',
        "const valueToCommit = queuedSliderRange;",
        "player.spawnParticle(VOICE_RANGE_PREVIEW_PARTICLE, location);",
        "Player-targeted particles keep the visualization private.",
    ]
    for marker in required:
        if marker not in text:
            raise RuntimeError(f"missing patched marker: {marker}")
    if "ใช้ระยะจาก Slider" in text:
        raise RuntimeError("legacy slider Apply button still exists")
    forbidden_ui = [
        "กำหนดระยะเอง",
        "ใช้ระยะที่กำหนด",
        '.header("Distance Volume")',
        '.button("0 • ปิดการลดเสียง"',
        '.button("1 • เบา"',
        '.button("2 • ปกติ"',
        '.button("3 • แรง"',
        '.button("4 • แรงมาก"',
        '.label(statusText)',
        '.label(offhandText)',
        '.label(rangeConfirmText)',
    ]
    for marker in forbidden_ui:
        if marker in text:
            raise RuntimeError(f"removed DDUI control returned: {marker}")

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

    validate_pack(bp, BP_UUID, "VC Mumble Item Mic BP v2.10.0")
    validate_pack(rp, RP_UUID, "VC Mumble Mic Icons RP v2.10.0")

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
