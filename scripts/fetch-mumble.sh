#!/usr/bin/env bash
set -euo pipefail

VERSION="${MUMBLE_VERSION:-1.5.915}"
EXPECTED_SHA256="${MUMBLE_SHA256:-2cb3f0c7aa60e2f08fed1d568da7d1f5115e3658a079b6d1a5468c5d6e2081d5}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${VC_BUILD_DIR:-${ROOT_DIR}/.build}"
ARCHIVE="${BUILD_DIR}/mumble-${VERSION}.tar.gz"
SOURCE_DIR="${BUILD_DIR}/mumble-${VERSION}"
URL="https://dl.mumble.info/stable/mumble-${VERSION}.tar.gz"

mkdir -p "${BUILD_DIR}"

if [[ ! -f "${ARCHIVE}" ]]; then
  echo "Downloading Mumble ${VERSION}..."
  curl --fail --location --retry 3 --output "${ARCHIVE}" "${URL}"
fi

echo "${EXPECTED_SHA256}  ${ARCHIVE}" | sha256sum --check --status || {
  echo "ERROR: Mumble source archive checksum mismatch" >&2
  rm -f "${ARCHIVE}"
  exit 2
}

echo "Verified mumble-${VERSION}.tar.gz SHA-256"

rm -rf "${SOURCE_DIR}"
tar -xzf "${ARCHIVE}" -C "${BUILD_DIR}"

python3 "${ROOT_DIR}/scripts/prepare-mumble-source.py" \
  --source "${SOURCE_DIR}" \
  --adapter "${ROOT_DIR}/native/mumble_android/AndroidEmbed.cpp" \
  --jni "${ROOT_DIR}/app/src/main/cpp/mumble_jni.cpp" \
  --proximity-header "${ROOT_DIR}/native/mumble_android/VCProximity.h" \
  --proximity-source "${ROOT_DIR}/native/mumble_android/VCProximity.cpp"

echo "Prepared Mumble Android source: ${SOURCE_DIR}"
