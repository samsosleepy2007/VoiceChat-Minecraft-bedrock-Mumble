#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "minecraft-addon" / "v2.8.0" / "VC_Mumble_ItemMic_v2.8.0.mcaddon.b64"
ADDON_NAME = "VC_Mumble_ItemMic_v2.8.0.mcaddon"
BP_NAME = "VC_Mumble_ItemMic_BP_v2.8.0.mcpack"
RP_NAME = "VC_Mumble_ItemMic_RP_v2.8.0.mcpack"
EXPECTED_SHA256 = "2e5da0b7692383af9b836544e3324bb46cde6185c3419e0ad86de7d850aab522"
VERSION = [2, 8, 0]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def validate_pack(pack_bytes: bytes, expected_uuid: str, expected_name: str) -> None:
    from io import BytesIO

    with zipfile.ZipFile(BytesIO(pack_bytes)) as archive:
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

    raw = base64.b64decode(SOURCE.read_text(encoding="ascii"))
    actual = sha256(raw)
    if actual != EXPECTED_SHA256:
        raise RuntimeError(f"Item Mic v2.8.0 checksum mismatch: {actual}")

    addon = output / ADDON_NAME
    addon.write_bytes(raw)

    with zipfile.ZipFile(addon) as outer:
        names = set(outer.namelist())
        assert BP_NAME in names
        assert RP_NAME in names
        bp = outer.read(BP_NAME)
        rp = outer.read(RP_NAME)

    validate_pack(
        bp,
        "b6411120-cc4e-44a9-b28d-f43b10cafd86",
        "VC Mumble Item Mic BP v2.8.0",
    )
    validate_pack(
        rp,
        "cb345edb-6e6c-49ac-9950-e2ae07bda214",
        "VC Mumble Mic Icons RP v2.8.0",
    )

    (output / BP_NAME).write_bytes(bp)
    (output / RP_NAME).write_bytes(rp)

    print(addon)
    print(f"sha256={actual}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
