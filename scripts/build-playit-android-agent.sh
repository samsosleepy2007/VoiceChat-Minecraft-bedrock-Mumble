#!/usr/bin/env bash
set -euo pipefail

PLAYIT_TAG="${PLAYIT_TAG:-v1.0.10}"
PLAYIT_COMMIT="9e7b9a1cb42d057e7993e21ef4fe32348d1e7fcd"
ANDROID_API="${PLAYIT_ANDROID_API:-28}"
NDK_HOME="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${ROOT_DIR}/.build/playit-agent"
STAGE_DIR="${ROOT_DIR}/.build/playit-android"
ABI_DIR="${STAGE_DIR}/arm64-v8a"

toolchain="${NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin"
linker="${toolchain}/aarch64-linux-android${ANDROID_API}-clang"

command -v cargo >/dev/null
command -v rustup >/dev/null
test -x "$linker"

rustup target add aarch64-linux-android >/dev/null

rm -rf "$SRC_DIR" "$STAGE_DIR"
mkdir -p "${ROOT_DIR}/.build" "$ABI_DIR"

git clone --depth 1 --branch "$PLAYIT_TAG"   https://github.com/playit-cloud/playit-agent.git "$SRC_DIR"

actual_commit="$(git -C "$SRC_DIR" rev-parse HEAD)"
if [[ "$actual_commit" != "$PLAYIT_COMMIT" ]]; then
  echo "playit-agent source mismatch: expected $PLAYIT_COMMIT, got $actual_commit" >&2
  exit 1
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$linker"
export CC_aarch64_linux_android="$linker"
export AR_aarch64_linux_android="${toolchain}/llvm-ar"

cargo build --locked --release --target aarch64-linux-android   --manifest-path "$SRC_DIR/Cargo.toml" -p playit-cli

cargo build --locked --release --target aarch64-linux-android   --manifest-path "$SRC_DIR/Cargo.toml" -p playitd --bin playitd

# Keep upstream binaries on the exact upstream Cargo.lock. Only after they are
# built do we add the small VC helper package to the cloned workspace.
cp -R "${ROOT_DIR}/native/playit_bridge" "${SRC_DIR}/packages/vc_mumble_helper"
python3 - "${SRC_DIR}/Cargo.toml" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text()
needle = '    "packages/api_client",\n]'
if needle not in text:
    raise SystemExit("playit workspace member anchor missing")
path.write_text(text.replace(
    needle,
    '    "packages/api_client",\n    "packages/vc_mumble_helper",\n]',
    1,
))
PY

cargo build --release --target aarch64-linux-android   --manifest-path "$SRC_DIR/Cargo.toml" -p vc-playit-helper

cli="${SRC_DIR}/target/aarch64-linux-android/release/playit-cli"
daemon="${SRC_DIR}/target/aarch64-linux-android/release/playitd"
helper="${SRC_DIR}/target/aarch64-linux-android/release/vc-playit-helper"

test -s "$cli"
test -s "$daemon"
test -s "$helper"

cp "$cli" "${ABI_DIR}/libplayit_cli_exec.so"
cp "$daemon" "${ABI_DIR}/libplayitd_exec.so"
cp "$helper" "${ABI_DIR}/libvc_playit_helper_exec.so"
chmod 0755   "${ABI_DIR}/libplayit_cli_exec.so"   "${ABI_DIR}/libplayitd_exec.so"   "${ABI_DIR}/libvc_playit_helper_exec.so"

cp "${SRC_DIR}/LICENSE.txt" "${STAGE_DIR}/LICENSE.playit-agent.txt"
printf '%s\n' "$actual_commit" > "${STAGE_DIR}/SOURCE_COMMIT.txt"

for bin in   "${ABI_DIR}/libplayit_cli_exec.so"   "${ABI_DIR}/libplayitd_exec.so"   "${ABI_DIR}/libvc_playit_helper_exec.so"; do
  file "$bin"
  readelf -l "$bin" | grep -F '/system/bin/linker64'
  readelf -d "$bin" | grep -F 'Shared library: [libc.so]'
done

sha256sum   "${ABI_DIR}/libplayit_cli_exec.so"   "${ABI_DIR}/libplayitd_exec.so"   "${ABI_DIR}/libvc_playit_helper_exec.so" | tee "${STAGE_DIR}/SHA256SUMS"

echo "Embedded playit Android payload staged at $STAGE_DIR"
