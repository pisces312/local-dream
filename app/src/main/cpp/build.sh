#!/usr/bin/env bash
set -e
# The presets set CMAKE_POLICY_VERSION_MINIMUM because MNN, msgpack and
# sentencepiece still declare cmake_minimum_required below what CMake 4
# accepts. Keeps the pinned submodules buildable as-is, with no working-tree
# patches to carry.
cmake --preset android-release "$@"
cmake --build --preset android-release

mkdir -p ../assets/qnnlibs ../jniLibs/arm64-v8a
cp build/android/qnnlibs/*.so ../assets/qnnlibs/
cp build/android/bin/arm64-v8a/libstable_diffusion_core.so ../jniLibs/arm64-v8a/
mkdir -p ../assets/licenses/qnn-2.50.0.260828
cp build/android/qnn-notices/* ../assets/licenses/qnn-2.50.0.260828/
(cd ../assets/qnnlibs && sha256sum *.so) > ../assets/licenses/qnn-2.50.0.260828/SHA256SUMS

# Record what produced the core. The app spawns it as an executable rather than
# loading it, so the manifest -- not the library -- is where the commit, the
# toolchain and the DIT_ENGINE_ABI_VERSION it was compiled against live. A
# failure here is not fatal; the app then reports the core as unrecorded.
# Git Bash hands a POSIX path to Windows python, which then reads it as C:\d\...
# -- the same trap build-sm8850.sh works around for zipalign. cygpath only
# exists on Windows, so other platforms get the path unchanged.
winpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

MAIN_DIR="$(cd .. && pwd)"
REPO_ROOT="$(cd "$MAIN_DIR/../../.." && pwd)"
CMAKE_VERSION="$(cmake --version 2>/dev/null | head -1 | awk '{print $3}')"
python "$(winpath "$REPO_ROOT/tools/collect-build-info.py")" \
    --name core \
    --out "$(winpath "$MAIN_DIR/assets/build-info/core.json")" \
    --repo "$(winpath "$REPO_ROOT")" \
    --abi-version "$(sed -n 's/^#define DIT_ENGINE_ABI_VERSION \([0-9]*\).*/\1/p' include/DitEngine.h)" \
    --toolchain-path "qairt=${QNN_SDK_ROOT:-${QAIRT_PATH:-unknown}}" \
    --toolchain "cmake=${CMAKE_VERSION:-unknown}" \
    --artifact "$(winpath "$MAIN_DIR/jniLibs/arm64-v8a/libstable_diffusion_core.so")" \
    || echo "WARNING: could not write the build-info manifest"
