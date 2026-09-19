#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_ROOT="${VC_BUILD_DIR:-${ROOT_DIR}/.build}"
SOURCE_DIR="${BUILD_ROOT}/mumble-1.6.870"
ANDROID_ABI="${VC_ANDROID_ABI:-arm64-v8a}"
NATIVE_BUILD_DIR="${BUILD_ROOT}/mumble-android-arm64"
EXTRA_STAGE_DIR="${BUILD_ROOT}/android-stage/extra-libs/${ANDROID_ABI}"
AAR_STAGE_DIR="${BUILD_ROOT}/android-stage"
AAR_OUT="${AAR_STAGE_DIR}/vc-mumble-runtime.aar"
ANDROID_PACKAGE_SOURCE_DIR="${ROOT_DIR}/native/mumble_android/android-package"

: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}"
: "${QT_ANDROID_PREFIX:?QT_ANDROID_PREFIX is required (Qt 6 Android arm64 prefix)}"
: "${QT_HOST_PATH:?QT_HOST_PATH is required (matching desktop Qt host tools)}"
: "${VC_ANDROID_DEP_PREFIX:?VC_ANDROID_DEP_PREFIX is required (Android native dependency prefix)}"
: "${PROTOC:?PROTOC is required (host-runnable protoc path)}"

if [[ ! -x "${PROTOC}" ]]; then
  echo "ERROR: PROTOC is not executable: ${PROTOC}" >&2
  exit 2
fi

if [[ ! -d "${ANDROID_NDK_HOME}" ]]; then
  echo "ERROR: Android NDK directory not found: ${ANDROID_NDK_HOME}" >&2
  exit 2
fi
if [[ ! -f "${ANDROID_PACKAGE_SOURCE_DIR}/settings.gradle" ]]; then
  echo "ERROR: Qt Android package overlay is missing settings.gradle: ${ANDROID_PACKAGE_SOURCE_DIR}" >&2
  exit 2
fi

QT_CMAKE="${QT_ANDROID_PREFIX}/bin/qt-cmake"
QT_TOOLCHAIN="${QT_ANDROID_PREFIX}/lib/cmake/Qt6/qt.toolchain.cmake"
QT6_CONFIG="${QT_ANDROID_PREFIX}/lib/cmake/Qt6/Qt6Config.cmake"
QT6_CORE_CONFIG="${QT_ANDROID_PREFIX}/lib/cmake/Qt6Core/Qt6CoreConfig.cmake"

if [[ ! -x "${QT_CMAKE}" ]]; then
  echo "ERROR: Qt Android qt-cmake not found or not executable: ${QT_CMAKE}" >&2
  exit 2
fi
for required_qt_file in "${QT_TOOLCHAIN}" "${QT6_CONFIG}" "${QT6_CORE_CONFIG}"; do
  if [[ ! -f "${required_qt_file}" ]]; then
    echo "ERROR: required Qt Android CMake file not found: ${required_qt_file}" >&2
    exit 2
  fi
done

"${ROOT_DIR}/scripts/fetch-mumble.sh"
rm -rf "${NATIVE_BUILD_DIR}" "${EXTRA_STAGE_DIR}" "${AAR_OUT}"
mkdir -p "${NATIVE_BUILD_DIR}" "${EXTRA_STAGE_DIR}" "${AAR_STAGE_DIR}"

configure_mumble() {
  local extra_libs="${1:-}"
  "${QT_CMAKE}" \
    -S "${SOURCE_DIR}" \
    -B "${NATIVE_BUILD_DIR}" \
    -G Ninja \
    -DANDROID_SDK_ROOT="${ANDROID_SDK_ROOT}" \
    -DANDROID_NDK_ROOT="${ANDROID_NDK_HOME}" \
    -DANDROID_ABI="${ANDROID_ABI}" \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DQT_HOST_PATH="${QT_HOST_PATH}" \
    -DQT_ADDITIONAL_PACKAGES_PREFIX_PATH="${VC_ANDROID_DEP_PREFIX}" \
    -DProtobuf_PROTOC_EXECUTABLE="${PROTOC}" \
    -DVC_ANDROID_EXTRA_LIBS="${extra_libs}" \
    -DVC_ANDROID_PACKAGE_SOURCE_DIR="${ANDROID_PACKAGE_SOURCE_DIR}" \
    -DBUILD_NUMBER=870 \
    -Ddebug-dependency-search=ON \
    -Dclient=OFF \
    -Dserver=ON \
    -Dplugins=OFF \
    -Dtests=OFF \
    -Dbenchmarks=OFF \
    -Doverlay=OFF \
    -Dzeroconf=OFF \
    -Dice=OFF \
    -Ddbus=OFF \
    -Denable-mysql=OFF \
    -Denable-postgresql=OFF \
    -Denable-sqlite=ON \
    -Dpackaging=OFF \
    -Dstatic=OFF \
    -Dlto=OFF \
    -Dwarnings-as-errors=OFF \
    -Dqssldiffiehellmanparameters=OFF
}

configure_mumble ""

echo "Resolved Qt Android CMake package directories:"
grep -E '^(Qt6|Qt6(Core|Gui|Network|Sql|Xml))_DIR:' "${NATIVE_BUILD_DIR}/CMakeCache.txt" || true

cmake --build "${NATIVE_BUILD_DIR}" --target mumble-server --parallel "${VC_BUILD_JOBS:-2}"

# Qt's Android executable helper emits ABI-qualified module names such as
# libvcserver_arm64-v8a.so. Keep that filename intact because androiddeployqt
# owns the final AAR layout; only discover and validate the actual target here.
CORE_SO="$(find "${NATIVE_BUILD_DIR}" -type f \
  \( -name 'libvcserver.so' -o -name "libvcserver_${ANDROID_ABI}.so" \) \
  -print -quit)"
if [[ -z "${CORE_SO}" ]]; then
  echo "ERROR: VC Mumble core library was not produced" >&2
  echo "Expected libvcserver.so or libvcserver_${ANDROID_ABI}.so under ${NATIVE_BUILD_DIR}" >&2
  find "${NATIVE_BUILD_DIR}" -type f -name 'libvcserver*.so' -print >&2 || true
  exit 3
fi

# llvm-readelf is shipped by recent Android NDKs as a symlink into the
# LLVM tool bundle. Follow symlinks while discovering it; plain "find -type f"
# misses the executable on NDK layouts where the entry itself is a symlink.
LLVM_READELF="$(find -L "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt" \
  -type f -name llvm-readelf -perm -111 -print -quit 2>/dev/null || true)"
if [[ -z "${LLVM_READELF}" || ! -x "${LLVM_READELF}" ]]; then
  echo "ERROR: llvm-readelf not found in Android NDK" >&2
  echo "NDK LLVM bin candidates:" >&2
  find "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt" -path '*/bin/llvm-read*' -print >&2 || true
  exit 4
fi
echo "Using Android ELF inspector: ${LLVM_READELF}"

CORE_MACHINE="$("${LLVM_READELF}" -h "${CORE_SO}" | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p' | head -n1)"
if [[ "${ANDROID_ABI}" == "arm64-v8a" && "${CORE_MACHINE}" != "AArch64" ]]; then
  echo "ERROR: core library has unexpected ELF machine: ${CORE_MACHINE:-unknown}" >&2
  exit 4
fi

echo "Embedded Mumble core produced:"
echo "  ${CORE_SO}"
echo "  ABI: ${ANDROID_ABI}"
echo "  ELF machine: ${CORE_MACHINE:-unknown}"

if ! "${LLVM_READELF}" --dyn-syms "${CORE_SO}" | grep -Eq 'GLOBAL[[:space:]]+DEFAULT[[:space:]]+[0-9]+[[:space:]]+main$'; then
  echo "ERROR: libvcserver does not export dynamic symbol main; Qt Android cannot resolve the native entrypoint" >&2
  "${LLVM_READELF}" --dyn-syms "${CORE_SO}" | grep -E 'main|JNI_OnLoad|NativeServer_' >&2 || true
  exit 4
fi
echo "Qt Android native entrypoint export: main OK"

CORE_DYNAMIC="$("${LLVM_READELF}" -d "${CORE_SO}")"
echo "Native DT_NEEDED entries before AAR deployment:"
printf '%s\n' "${CORE_DYNAMIC}" | grep 'Shared library:' || true
if ! printf '%s\n' "${CORE_DYNAMIC}" | grep -Eq 'Shared library: \[libQt6Gui(_[^]]+)?\.so\]'; then
  echo "ERROR: core does not retain Qt Gui in DT_NEEDED; androiddeployqt cannot deploy the Android platform plugin" >&2
  exit 4
fi

is_android_system_or_qt_lib() {
  case "$1" in
    libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libz.so|libEGL.so|libGLESv2.so|libGLESv3.so|libOpenSLES.so|libjnigraphics.so|libmediandk.so|libvulkan.so|libaaudio.so|libcamera2ndk.so|libQt6*.so|libplugins_*.so)
      return 0 ;;
    *)
      return 1 ;;
  esac
}

find_external_candidate() {
  local needed="$1"
  local candidate=""

  candidate="$(find -L "${VC_ANDROID_DEP_PREFIX}" -type f -name "${needed}" -print -quit 2>/dev/null || true)"
  if [[ -z "${candidate}" && "${needed}" == "libc++_shared.so" ]]; then
    candidate="$(find "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt" -type f -path '*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so' -print -quit)"
  fi
  printf '%s' "${candidate}"
}

stage_external_lib() {
  local source="$1"
  cp -Lf "${source}" "${EXTRA_STAGE_DIR}/$(basename "${source}")"
}

# Resolve only non-Qt DT_NEEDED libraries. Qt libraries/plugins are deliberately
# left to androiddeployqt when the AAR target runs.
scan_queue=("${CORE_SO}")
scan_index=0
while (( scan_index < ${#scan_queue[@]} )); do
  current="${scan_queue[scan_index]}"
  ((scan_index+=1))
  while IFS= read -r needed; do
    [[ -n "${needed}" ]] || continue
    is_android_system_or_qt_lib "${needed}" && continue
    if [[ -f "${EXTRA_STAGE_DIR}/${needed}" ]]; then
      continue
    fi
    candidate="$(find_external_candidate "${needed}")"
    if [[ -z "${candidate}" ]]; then
      echo "ERROR: unresolved external Android dependency: $(basename "${current}") -> ${needed}" >&2
      exit 5
    fi
    stage_external_lib "${candidate}"
    scan_queue+=("${EXTRA_STAGE_DIR}/${needed}")
  done < <("${LLVM_READELF}" -d "${current}" | sed -n 's/.*Shared library: \[\(.*\)\].*/\1/p')
done

EXTRA_LIBS_CMAKE=""
while IFS= read -r lib; do
  if [[ -n "${EXTRA_LIBS_CMAKE}" ]]; then
    EXTRA_LIBS_CMAKE+=";"
  fi
  EXTRA_LIBS_CMAKE+="${lib}"
done < <(find "${EXTRA_STAGE_DIR}" -maxdepth 1 -type f -name '*.so*' -print | sort)

# Feed resolved non-Qt libraries back to Qt's deployment metadata, then let the
# official Qt AAR target package Qt runtime/plugins + Mumble + those libraries.
configure_mumble "${EXTRA_LIBS_CMAKE}"
cmake --build "${NATIVE_BUILD_DIR}" --target mumble-server_make_aar --parallel "${VC_BUILD_JOBS:-2}"

GENERATED_AAR="$(find "${NATIVE_BUILD_DIR}" -type f -name '*.aar' -printf '%T@ %p\n' | sort -nr | head -n1 | cut -d' ' -f2-)"
if [[ -z "${GENERATED_AAR}" || ! -f "${GENERATED_AAR}" ]]; then
  echo "ERROR: Qt AAR target completed but no .aar was found" >&2
  exit 6
fi
cp -f "${GENERATED_AAR}" "${AAR_OUT}"

# Fail in CI now rather than later on a phone if core/runtime packaging is incomplete.
unzip -l "${AAR_OUT}" > "${AAR_STAGE_DIR}/vc-mumble-runtime.contents.txt"
if ! grep -Eq 'libvcserver(_[^/]+)?\.so' "${AAR_STAGE_DIR}/vc-mumble-runtime.contents.txt"; then
  echo "ERROR: AAR does not contain the VC Mumble core library" >&2
  exit 7
fi
if ! grep -q 'Qt6Core' "${AAR_STAGE_DIR}/vc-mumble-runtime.contents.txt"; then
  echo "ERROR: AAR does not contain Qt Core runtime" >&2
  exit 7
fi
if ! grep -q 'Qt6Network' "${AAR_STAGE_DIR}/vc-mumble-runtime.contents.txt"; then
  echo "ERROR: AAR does not contain Qt Network runtime" >&2
  exit 7
fi
if ! grep -q 'Qt6Sql' "${AAR_STAGE_DIR}/vc-mumble-runtime.contents.txt"; then
  echo "ERROR: AAR does not contain Qt SQL runtime" >&2
  exit 7
fi
if ! grep -q 'Qt6Gui' "${AAR_STAGE_DIR}/vc-mumble-runtime.contents.txt"; then
  echo "ERROR: AAR does not contain Qt Gui runtime required by Android platform plugin" >&2
  exit 7
fi
if ! grep -Eqi 'platforms.*qtforandroid|libplugins_platforms_qtforandroid' "${AAR_STAGE_DIR}/vc-mumble-runtime.contents.txt"; then
  echo "ERROR: AAR does not contain the Qt Android platform plugin" >&2
  exit 7
fi
if ! grep -Eqi 'qsqlite|sqldrivers.*sqlite' "${AAR_STAGE_DIR}/vc-mumble-runtime.contents.txt"; then
  echo "ERROR: AAR does not contain Qt SQLite driver" >&2
  exit 7
fi

CLASSES_JAR="$(mktemp)"
trap 'rm -f "${CLASSES_JAR}"' EXIT
unzip -p "${AAR_OUT}" classes.jar > "${CLASSES_JAR}"
if ! jar tf "${CLASSES_JAR}" | grep -q 'org/qtproject/qt/android/bindings/QtService.class'; then
  echo "ERROR: AAR classes.jar does not contain QtService" >&2
  exit 8
fi

echo "Native dependencies of $(basename "${CORE_SO}"):"
"${LLVM_READELF}" -d "${CORE_SO}" | grep NEEDED || true

echo
echo "External libraries handed to androiddeployqt:"
find "${EXTRA_STAGE_DIR}" -maxdepth 1 -type f -printf '  %f\n' | sort || true

echo
echo "VC Mumble Qt runtime AAR:"
echo "  ${AAR_OUT}"
echo "AAR inventory:"
echo "  ${AAR_STAGE_DIR}/vc-mumble-runtime.contents.txt"
echo
echo "Build the APK with:"
echo "  gradle -PvcMumbleCore=true :app:assembleDebug"
