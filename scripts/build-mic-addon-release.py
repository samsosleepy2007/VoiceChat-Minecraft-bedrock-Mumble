#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import tarfile
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "minecraft-addon" / "v2.7.4"
PARTS = [
    ("source.tgz.b64.part0", "eb2e115c2d5314c36a8a8718ce1b4f9bde3a737f04ab633ff01a09d1e6bb3993"),
    ("source.tgz.b64.part1", "80f400eb9f0fef141084df7aabe387534e6556131dd8410f5485c49ceb4edad4"),
    ("source.tgz.b64.part2.0", "c5f8e6165a2649e6a021fe81146c7aec3a532fa14936dd5f2079325f940ad21b"),
    ("source.tgz.b64.part2.1", "99be147b9cacb529d7a10d3d133765faedc4fcfb7ea2a2f847f7107fca1156e6"),
    ("source.tgz.b64.part2.2", "198daeaf814c86079aab2c8e4238aef32e7c3c833ff58467acdc78482242ee84"),
    ("source.tgz.b64.part2.3", "563eb51d684a5387fb05b7fa944150a26b559e190fe583291c91551110303c4a"),
    ("source.tgz.b64.part2.4", "5ed76d14f7abb7c7ef6c9c071a7f11838d561f78b8362c3094fefd388c3ca512"),
    ("source.tgz.b64.part2.5", "11bba00c2d3ee881773c902c08086d17fb5b0fe5499071dfb926ef8ee48672c8"),
    ("source.tgz.b64.part2.6", "f68e9eca3e2d683ff9a37068a7983dfb2b8fb5eec7f2d0e85d0cc714c974dc78"),
    ("source.tgz.b64.part3", "ae1d28c0b545ced2e28ff545d4c00a03b34f91fb1dde29f07a5cc3389a1b1f33"),
]
SOURCE_TGZ_SHA256 = "f66a7633d4d923e49efd5236f382747e21205e9448454dda9ffa5f074cc33c8f"
VERSION = [2, 7, 6]

OLD_MIC_STATE = """function publishMicState(player, on) {
  try {
    const wanted = on ? MIC_ON_TAG : MIC_OFF_TAG;
    const unwanted = on ? MIC_OFF_TAG : MIC_ON_TAG;

    if (player.hasTag(unwanted)) player.removeTag(unwanted);
    if (!player.hasTag(wanted)) player.addTag(wanted);
  } catch (e) {
    console.warn(\`[VCMumbleItem/BP] mic tag sync failed player=\${player.name}: \${e}\`);
  }
}
"""

NEW_MIC_STATE = """function publishMicState(player, on) {
  const wanted = on ? MIC_ON_TAG : MIC_OFF_TAG;
  const unwanted = on ? MIC_OFF_TAG : MIC_ON_TAG;

  try {
    // Keep the two bridge tags strictly mutually exclusive. Some worlds can
    // retain an old OFF tag while the visual Mic item has already switched ON.
    try {
      if (player.hasTag(unwanted)) player.removeTag(unwanted);
    } catch {}
    try {
      if (!player.hasTag(wanted)) player.addTag(wanted);
    } catch {}

    let tags = [];
    try {
      tags = player.getTags();
    } catch {}
    const correct = tags.includes(wanted) && !tags.includes(unwanted);

    // Command fallback repairs tag state if Script API tag mutation did not
    // become visible immediately to Endstone. Only runs when verification fails.
    if (!correct) {
      try { player.runCommand(\`tag @s remove \${unwanted}\`); } catch {}
      try { player.runCommand(\`tag @s add \${wanted}\`); } catch {}
    }
  } catch (e) {
    console.warn(\`[VCMumbleItem/BP] mic tag sync failed player=\${player.name}: \${e}\`);
  }
}
"""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def reconstruct_source() -> bytes:
    encoded_parts: list[str] = []
    for name, expected in PARTS:
        path = SOURCE_DIR / name
        data = path.read_bytes()
        actual = sha256(data)
        if actual != expected:
            raise RuntimeError(f"{path}: checksum mismatch {actual} != {expected}")
        encoded_parts.append(data.decode("ascii"))

    tgz = base64.b64decode("".join(encoded_parts))
    actual = sha256(tgz)
    if actual != SOURCE_TGZ_SHA256:
        raise RuntimeError(f"source.tgz checksum mismatch {actual} != {SOURCE_TGZ_SHA256}")
    return tgz


def update_manifest(path: Path, pack: str) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    data["header"]["version"] = VERSION
    if pack == "bp":
        data["header"]["name"] = "VC Mumble Item Mic BP v2.7.6"
        data["header"]["description"] = (
            "VoiceCraft Item Mic adapted for VC Mumble: native vcmumble.mic "
            "ON/OFF tags and Endstone voice-range sync."
        )
        for module in data.get("modules", []):
            module["version"] = VERSION
        for dep in data.get("dependencies", []):
            if dep.get("uuid") == "cb345edb-6e6c-49ac-9950-e2ae07bda214":
                dep["version"] = VERSION
    else:
        data["header"]["name"] = "VC Mumble Mic Icons RP v2.7.6"
        data["header"]["description"] = (
            "Inventory icons and invisible held Mic model for VC Mumble Item Mic v2.7.6."
        )
        for module in data.get("modules", []):
            module["version"] = VERSION
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def patch_script(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if OLD_MIC_STATE not in text:
        raise RuntimeError("v2.7.4 publishMicState anchor not found")
    text = text.replace(OLD_MIC_STATE, NEW_MIC_STATE, 1)
    old_banner = (
        "[VCMumbleItem/BP] Loaded v2.7.4 — VC Mumble native mic/range contract "
        "(feature/minecraft-mic-addon-v1)"
    )
    new_banner = (
        "[VCMumbleItem/BP] Loaded v2.7.6 — VC Mumble native mic/range contract "
        "(feature/minecraft-mic-addon-v1)"
    )
    if old_banner not in text:
        raise RuntimeError("v2.7.4 version banner anchor not found")
    path.write_text(text.replace(old_banner, new_banner, 1), encoding="utf-8")


def zip_tree(source: Path, target: Path) -> None:
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(source).as_posix())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="vc-mic-addon-") as raw:
        temp = Path(raw)
        tgz_path = temp / "source.tgz"
        tgz_path.write_bytes(reconstruct_source())

        src = temp / "src"
        src.mkdir()
        with tarfile.open(tgz_path, "r:gz") as archive:
            archive.extractall(src)

        bp = src / "bp"
        rp = src / "rp"
        update_manifest(bp / "manifest.json", "bp")
        update_manifest(rp / "manifest.json", "rp")
        patch_script(bp / "scripts" / "main.js")

        # Keep the pack icon self-contained and reproducible from repository
        # source. The inventory Mic icon is used for both BP/RP pack icons.
        pack_icon = rp / "textures" / "items" / "icon_mic_on.png"
        shutil.copy2(pack_icon, bp / "pack_icon.png")
        shutil.copy2(pack_icon, rp / "pack_icon.png")

        bp_pack = output / "VC_Mumble_ItemMic_BP_v2.7.6.mcpack"
        rp_pack = output / "VC_Mumble_ItemMic_RP_v2.7.6.mcpack"
        addon = output / "VC_Mumble_ItemMic_v2.7.6.mcaddon"
        zip_tree(bp, bp_pack)
        zip_tree(rp, rp_pack)

        outer = temp / "outer"
        outer.mkdir()
        shutil.copy2(bp_pack, outer / bp_pack.name)
        shutil.copy2(rp_pack, outer / rp_pack.name)
        zip_tree(outer, addon)

    with zipfile.ZipFile(addon) as outer_zip:
        names = set(outer_zip.namelist())
        assert "VC_Mumble_ItemMic_BP_v2.7.6.mcpack" in names
        assert "VC_Mumble_ItemMic_RP_v2.7.6.mcpack" in names

    print(addon)
    print(f"sha256={sha256(addon.read_bytes())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
