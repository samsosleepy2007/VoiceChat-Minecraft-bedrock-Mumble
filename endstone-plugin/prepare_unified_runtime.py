from __future__ import annotations

import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
VENDOR = ROOT / "vendor" / "endstone_mumble_host-0.3.0-py3-none-any.whl"
TARGET = ROOT / "src" / "endstone_vc_mumble" / "bin" / "mumble-server-vc"
MEMBER = "endstone_mumble_host/bin/mumble-server-vc"


def main() -> int:
    if not VENDOR.is_file():
        raise SystemExit(f"missing vendor wheel: {VENDOR}")

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(VENDOR) as archive:
        data = archive.read(MEMBER)
    TARGET.write_bytes(data)
    TARGET.chmod(0o755)
    print(f"prepared {TARGET} ({len(data)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
