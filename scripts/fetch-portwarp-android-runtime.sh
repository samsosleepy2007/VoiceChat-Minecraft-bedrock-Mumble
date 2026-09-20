#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKSUMS_URL="https://portwarp.com/download/checksums.txt"
DOWNLOAD_BASE="https://portwarp.com/download"
DEST_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
DEST_FILE="${DEST_DIR}/libpwrp_exec.so"

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

echo "Packaged PortWarp runtime:"
file "${DEST_FILE}"
echo "archive=${archive}"
echo "sha256=${actual}"
echo "destination=${DEST_FILE}"
