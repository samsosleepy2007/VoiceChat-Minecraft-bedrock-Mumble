from __future__ import annotations

import base64
import hashlib
import re
import struct
from pathlib import Path


ASSETS = {
    Path("app/src/main/java/com/voicecraft/vcmumbleserver/McsvLogoAsset.java"): {
        "sha256": "24a1a4491a1a5857d571f2f1fde3fb1695e306c211f7209bc1a9d0bab32a2825",
        "width": 220,
        "height": 59,
    },
    Path("app/src/main/java/com/voicecraft/vcmumbleserver/EndstoneLogoAsset.java"): {
        "sha256": "c90d90a264d4d5564acf2596312c48b9057d4b876dc30a393eea8a040694b35b",
        "width": 280,
        "height": 280,
    },
}


def extract_embedded_asset(path: Path) -> bytes:
    text = path.read_text(encoding="utf-8")
    marker = "private static final String DATA ="
    assert marker in text, f"{path}: DATA marker missing"
    body = text.split(marker, 1)[1].split(";", 1)[0]
    chunks = re.findall(r'"([A-Za-z0-9+/=]+)"', body)
    assert chunks, f"{path}: no Base64 chunks found"
    return base64.b64decode("".join(chunks), validate=True)


def webp_dimensions(data: bytes) -> tuple[int, int]:
    assert data[:4] == b"RIFF", "WEBP RIFF signature missing"
    assert data[8:12] == b"WEBP", "WEBP container signature missing"

    offset = 12
    while offset + 8 <= len(data):
        chunk_type = data[offset:offset + 4]
        chunk_size = struct.unpack_from("<I", data, offset + 4)[0]
        payload = offset + 8
        end = payload + chunk_size
        assert end <= len(data), f"WEBP chunk {chunk_type!r} is truncated"

        if chunk_type == b"VP8L":
            assert chunk_size >= 5, "VP8L chunk is too short"
            assert data[payload] == 0x2F, "VP8L signature byte missing"
            bits = int.from_bytes(data[payload + 1:payload + 5], "little")
            width = (bits & 0x3FFF) + 1
            height = ((bits >> 14) & 0x3FFF) + 1
            return width, height

        if chunk_type == b"VP8X":
            assert chunk_size >= 10, "VP8X chunk is too short"
            width = 1 + int.from_bytes(data[payload + 4:payload + 7], "little")
            height = 1 + int.from_bytes(data[payload + 7:payload + 10], "little")
            return width, height

        offset = end + (chunk_size & 1)

    raise AssertionError("WEBP dimension chunk was not found")


def main() -> None:
    for path, expected in ASSETS.items():
        data = extract_embedded_asset(path)
        digest = hashlib.sha256(data).hexdigest()
        assert digest == expected["sha256"], (
            f"{path}: SHA-256 mismatch: {digest} != {expected['sha256']}"
        )
        width, height = webp_dimensions(data)
        assert (width, height) == (expected["width"], expected["height"]), (
            f"{path}: unexpected dimensions {width}x{height}"
        )
        print(
            f"{path.name}: WEBP {width}x{height}, "
            f"sha256={digest}, bytes={len(data)}"
        )


if __name__ == "__main__":
    main()
