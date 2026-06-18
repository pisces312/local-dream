#!/usr/bin/env bash
# Local Dream Build Script
# Usage: ./build.sh [debug|release] [basic|filter] [--rebuild-native] [--skip-gradle] [-d <device>]
#   (default: release basic)
#
# C++ backend (libstable_diffusion_core.so) is pre-built and placed in
# jniLibs/arm64-v8a/. The Gradle build packages it as-is.
# Use --rebuild-native to rebuild the C++ backend (requires QNN SDK + NDK).
#
# Examples:
#   ./build.sh                           # Build release, basic flavor
#   ./build.sh debug                     # Build debug, basic flavor
#   ./build.sh release filter            # Build release, filter flavor (with safety checker)
#   ./build.sh debug basic -d 192.168.1.5:5555  # Build debug, install to device
#   ./build.sh release --rebuild-native  # Rebuild C++ backend then build APK

set -e

# ─── Defaults ───
BUILD_TYPE="release"
FLAVOR="basic"
REBUILD_NATIVE=false
SKIP_GRADLE=false
DEVICE=""

# ─── Parse args ───
while [[ $# -gt 0 ]]; do
    case "$1" in
        debug|release) BUILD_TYPE="$1"; shift ;;
        basic|filter)  FLAVOR="$1"; shift ;;
        --rebuild-native) REBUILD_NATIVE=true; shift ;;
        --skip-gradle)   SKIP_GRADLE=true; shift ;;
        -d) DEVICE="$2"; shift 2 ;;
        *) echo "Usage: $0 [debug|release] [basic|filter] [--rebuild-native] [--skip-gradle] [-d <device>]"; exit 1 ;;
    esac
done

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$PROJECT_DIR/app"
CPP_DIR="$APP_DIR/src/main/cpp"
JNI_DIR="$APP_DIR/src/main/jniLibs/arm64-v8a"
QNN_ASSETS_DIR="$APP_DIR/src/main/assets/qnnlibs"

# Auto-detect version from build.gradle.kts
VERSION=""
GRADLE_FILE="$APP_DIR/build.gradle.kts"
if [[ -f "$GRADLE_FILE" ]]; then
    VERSION=$(grep 'versionName' "$GRADLE_FILE" | head -1 | sed 's/.*versionName *= *"\([^"]*\)".*/\1/')
fi
VERSION="${VERSION:-2.7.0}"

BUILD_TYPE_CAP="$(echo "$BUILD_TYPE" | sed 's/\b./\u&/')"
FLAVOR_CAP="$(echo "$FLAVOR" | sed 's/\b./\u&/')"
GRADLE_TASK="assemble${FLAVOR_CAP}${BUILD_TYPE_CAP}"

echo "=== Building LocalDream v${VERSION} | ${BUILD_TYPE} | ${FLAVOR} ==="

# ─── Step 1: Optionally rebuild C++ backend ───
if [[ "$REBUILD_NATIVE" == true ]]; then
    echo "=== Step 1: Rebuilding native backend ==="

    if [[ -z "$ANDROID_NDK_ROOT" ]]; then
        echo "ERROR: ANDROID_NDK_ROOT not set"
        echo "  export ANDROID_NDK_ROOT=/path/to/android-ndk-r28"
        exit 1
    fi

    if [[ ! -f "$CPP_DIR/CMakeLists.txt" ]]; then
        echo "ERROR: CMakeLists.txt not found at $CPP_DIR"
        exit 1
    fi

    # Update CMakePresets.json NDK path for Windows/Linux
    echo "  NDK: $ANDROID_NDK_ROOT"

    cd "$CPP_DIR"
    cmake --preset android-release \
        -DCMAKE_ANDROID_NDK="$ANDROID_NDK_ROOT" \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    cmake --build --preset android-release

    # Copy outputs
    mkdir -p "$JNI_DIR"
    cp -f "$CPP_DIR/build/android/bin/arm64-v8a/libstable_diffusion_core.so" "$JNI_DIR/"
    echo "  Copied libstable_diffusion_core.so → jniLibs/arm64-v8a/"

    mkdir -p "$QNN_ASSETS_DIR"
    cp -rf "$CPP_DIR/build/android/qnnlibs/"* "$QNN_ASSETS_DIR/"
    echo "  Copied qnnlibs/ → assets/qnnlibs/"

    echo "=== Native backend rebuilt ==="
else
    # Verify pre-built .so exists
    if [[ ! -f "$JNI_DIR/libstable_diffusion_core.so" ]]; then
        echo "ERROR: libstable_diffusion_core.so not found in jniLibs/"
        echo "  Either run with --rebuild-native (requires QNN SDK + NDK)"
        echo "  Or extract from official APK and place in $JNI_DIR/"
        exit 1
    fi
    echo "=== Using pre-built native backend (skip C++ compile) ==="
fi

if [[ "$SKIP_GRADLE" == true ]]; then
    echo "=== Skipping Gradle build ==="
    exit 0
fi

# ─── Step 2: Gradle build ───
echo "=== Step 2: Gradle build ($GRADLE_TASK) ==="
cd "$PROJECT_DIR"
./gradlew "$GRADLE_TASK"

# ─── Step 3: Locate APK ───
BUILD_DIR="$APP_DIR/build/outputs/apk/$FLAVOR/$BUILD_TYPE"
APK_NAME="LocalDream_armv8a_${VERSION}.apk"

if [[ "$BUILD_TYPE" == "release" ]]; then
    SOURCE_APK="$BUILD_DIR/LocalDream_armv8a_${VERSION}.apk"
    if [[ ! -f "$SOURCE_APK" ]]; then
        SOURCE_APK="$BUILD_DIR/LocalDream_armv8a_${VERSION}-unsigned.apk"
    fi
    if [[ ! -f "$SOURCE_APK" ]]; then
        SOURCE_APK=$(ls "$BUILD_DIR"/*.apk 2>/dev/null | head -1)
    fi
else
    SOURCE_APK="$BUILD_DIR/LocalDream_armv8a_${VERSION}.apk"
    if [[ ! -f "$SOURCE_APK" ]]; then
        SOURCE_APK=$(ls "$BUILD_DIR"/*.apk 2>/dev/null | head -1)
    fi
fi

if [[ ! -f "$SOURCE_APK" ]]; then
    echo "ERROR: APK not found in $BUILD_DIR"
    ls "$BUILD_DIR" 2>/dev/null || true
    exit 1
fi

# ─── Step 4: Sign (release) or copy (debug) ───
OUTPUT_APK=""
if [[ "$BUILD_TYPE" == "release" ]]; then
    # Signing
    KEYSTORE="${KEY_STORE:-}"
    KEYSTORE_PASS="${KEY_STORE_PASSWORD:-}"
    KEY_ALIAS="${KEY_ALIAS:-pisces312}"

    if [[ -z "$KEYSTORE" ]] || [[ -z "$KEYSTORE_PASS" ]]; then
        echo "WARNING: Signing config not set (KEY_STORE / KEY_STORE_PASSWORD env vars)"
        echo "  Copying unsigned APK instead"
        OUTPUT_APK="$PROJECT_DIR/LocalDream_armv8a_${VERSION}-${FLAVOR}-unsigned.apk"
        cp -f "$SOURCE_APK" "$OUTPUT_APK"
    else
        BUILD_TOOLS="${ANDROID_SDK_ROOT:-D:/dev/android_sdk}/build-tools/34.0.0"
        ALIGNED_APK="$BUILD_DIR/release-aligned.apk"
        OUTPUT_APK="$PROJECT_DIR/LocalDream_armv8a_${VERSION}-${FLAVOR}-signed.apk"

        echo "=== Aligning ==="
        "$BUILD_TOOLS/zipalign" -f 4 "$SOURCE_APK" "$ALIGNED_APK"

        echo "=== Signing ==="
        java -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
            --ks "$KEYSTORE" \
            --ks-pass "pass:$KEYSTORE_PASS" \
            --ks-key-alias "$KEY_ALIAS" \
            --key-pass "pass:$KEYSTORE_PASS" \
            --out "$OUTPUT_APK" \
            "$ALIGNED_APK"

        rm -f "$ALIGNED_APK"
    fi
else
    OUTPUT_APK="$PROJECT_DIR/LocalDream_armv8a_${VERSION}-${FLAVOR}-debug.apk"
    cp -f "$SOURCE_APK" "$OUTPUT_APK"
fi

SIZE=$(du -h "$OUTPUT_APK" | cut -f1)
echo "=== Done: $OUTPUT_APK ($SIZE) ==="

# ─── Step 5: Install to device (optional) ───
if [[ -n "$DEVICE" ]]; then
    if ! command -v adb &> /dev/null; then
        echo "ERROR: adb not found in PATH"
        exit 1
    fi

    echo "=== Installing to $DEVICE ==="
    adb connect "$DEVICE" 2>/dev/null || true
    adb -s "$DEVICE" install -r "$OUTPUT_APK"
    echo "=== Installed ==="
fi
