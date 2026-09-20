#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKSUMS_URL="https://portwarp.com/download/checksums.txt"
DOWNLOAD_BASE="https://portwarp.com/download"
DEST_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
DEST_FILE="${DEST_DIR}/libpwrp_exec.so"
LAUNCHER_SRC="${ROOT_DIR}/native/portwarp_dns_launcher.c"
LAUNCHER_FILE="${DEST_DIR}/libpwrp_dns_launcher_exec.so"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Fetching official PortWarp checksum manifest"
curl -fsSL --retry 3 "${CHECKSUMS_URL}" -o "${tmp}/checksums.txt"

archive="$(
  awk '
    $1 ~ /^[0-9a-fA-F]{64}$/ && $2 ~ /^pwrp-[0-9]+\.[0-9]+\.[0-9]+-linux-arm64\.tar\.gz$/ {
      print $2
    }
  ' "${tmp}/checksums.txt" |
  sort -V |
  tail -1
)"

if [[ -z "${archive}" ]]; then
  echo "ERROR: no checksum-pinned Linux ARM64 PortWarp archive found" >&2
  exit 1
fi

expected="$(
  awk -v f="${archive}" '$2 == f { print tolower($1); exit }' "${tmp}/checksums.txt"
)"
if [[ ! "${expected}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "ERROR: invalid checksum for ${archive}" >&2
  exit 1
fi

echo "Downloading official ${archive}"
curl -fL --retry 3 "${DOWNLOAD_BASE}/${archive}" -o "${tmp}/${archive}"

actual="$(sha256sum "${tmp}/${archive}" | awk '{print tolower($1)}')"
if [[ "${actual}" != "${expected}" ]]; then
  echo "ERROR: PortWarp SHA-256 mismatch" >&2
  echo "expected=${expected}" >&2
  echo "actual=${actual}" >&2
  exit 1
fi
echo "PortWarp SHA-256 verified: ${actual}"

mkdir -p "${tmp}/extracted"
tar -xzf "${tmp}/${archive}" -C "${tmp}/extracted"
src="$(find "${tmp}/extracted" -type f -name pwrp -print -quit)"
if [[ -z "${src}" || ! -s "${src}" ]]; then
  echo "ERROR: pwrp executable missing from ${archive}" >&2
  exit 1
fi

mkdir -p "${DEST_DIR}"
install -m 0755 "${src}" "${DEST_FILE}"

python3 - "${DEST_FILE}" <<'PY'
from pathlib import Path
import hashlib
import sys

path = Path(sys.argv[1])
data = path.read_bytes()
old = b"/etc/resolv.conf"
new = b"/proc/self/fd/10"

if len(old) != len(new):
    raise SystemExit("PortWarp DNS compatibility paths must be equal length")
count = data.count(old)
if count != 1:
    raise SystemExit(f"expected exactly one /etc/resolv.conf marker, found {count}")

patched = data.replace(old, new, 1)
path.write_bytes(patched)
print("PortWarp Android DNS compatibility patch applied")
print("patched_sha256=" + hashlib.sha256(patched).hexdigest())
PY
chmod 0755 "${DEST_FILE}"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ERROR: ANDROID_NDK_HOME is required to build PortWarp DNS launcher" >&2
  exit 1
fi

cc="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
if [[ ! -x "${cc}" ]]; then
  echo "ERROR: Android ARM64 clang not found: ${cc}" >&2
  exit 1
fi
if [[ ! -f "${LAUNCHER_SRC}" ]]; then
  echo "ERROR: PortWarp DNS launcher source missing: ${LAUNCHER_SRC}" >&2
  exit 1
fi

"${cc}" -O2 -fPIE -pie -Wall -Wextra -Werror   "${LAUNCHER_SRC}" -o "${LAUNCHER_FILE}"
chmod 0755 "${LAUNCHER_FILE}"

echo "Packaged PortWarp runtime:"
file "${DEST_FILE}"
file "${LAUNCHER_FILE}"
echo "archive=${archive}"
echo "official_sha256=${actual}"
echo "pwrp_destination=${DEST_FILE}"
echo "launcher_destination=${LAUNCHER_FILE}"
