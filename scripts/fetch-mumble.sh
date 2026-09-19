#!/usr/bin/env bash
set -euo pipefail

VERSION="${MUMBLE_VERSION:-1.6.870}"
EXPECTED_SHA256="${MUMBLE_SHA256:-cd4726e36538d09b2fa4f7445cbe0be5cb1fcf642c2ea31ae6a6ed04d60e2513}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${VC_BUILD_DIR:-${ROOT_DIR}/.build}"
ARCHIVE="${BUILD_DIR}/mumble-${VERSION}.tar.gz"
SOURCE_DIR="${BUILD_DIR}/mumble-${VERSION}"
URL="https://github.com/mumble-voip/mumble/releases/download/v${VERSION}/mumble-${VERSION}.tar.gz"

mkdir -p "${BUILD_DIR}"

download_archive() {
  local part="${ARCHIVE}.part"
  local attempt
  rm -f "${part}"

  for attempt in 1 2 3 4 5; do
    echo "Downloading Mumble ${VERSION} (attempt ${attempt}/5)..."
    rm -f "${part}"
    if curl \
      --fail \
      --location \
      --connect-timeout 20 \
      --max-time 300 \
      --retry 2 \
      --retry-delay 2 \
      --retry-all-errors \
      --output "${part}" \
      "${URL}"; then
      mv -f "${part}" "${ARCHIVE}"
      return 0
    fi

    echo "WARN: Mumble source download attempt ${attempt} failed" >&2
    sleep "$((attempt * 2))"
  done

  rm -f "${part}"
  echo "ERROR: unable to download Mumble ${VERSION} after 5 attempts" >&2
  return 1
}

if [[ ! -f "${ARCHIVE}" ]]; then
  download_archive
fi

if ! echo "${EXPECTED_SHA256}  ${ARCHIVE}" | sha256sum --check --status; then
  echo "WARN: cached Mumble source checksum mismatch; downloading a clean copy" >&2
  rm -f "${ARCHIVE}"
  download_archive
fi

echo "${EXPECTED_SHA256}  ${ARCHIVE}" | sha256sum --check --status || {
  echo "ERROR: Mumble source archive checksum mismatch after fresh download" >&2
  rm -f "${ARCHIVE}" "${ARCHIVE}.part"
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
