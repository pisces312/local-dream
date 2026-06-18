#!/usr/bin/env bash
# Local Dream Build Script — SM8850 (Snapdragon 8 Elite 2nd gen) only
# Builds normally, then strips non-V81 QNN libs from the APK before signing.
# Source assets directory is never modified.
#
# Usage: ./build-sm8850.sh [debug|release] [basic|filter] [-d <device>]
#   (default: release basic)

set -e

# ─── Defaults ───
BUILD_TYPE="release"
FLAVOR="basic"
DEVICE=""

# ─── Parse args ───
while [[ $# -gt 0 ]]; do
    case "$1" in
        debug|release) BUILD_TYPE="$1"; shift ;;
        basic|filter)  FLAVOR="$1"; shift ;;
        -d) DEVICE="$2"; shift 2 ;;
        *) echo "Usage: $0 [debug|release] [basic|filter] [-d <device>]"; exit 1 ;;
    esac
done

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$PROJECT_DIR/app"

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

echo "=== Building LocalDream v${VERSION} | ${BUILD_TYPE} | ${FLAVOR} | SM8850 only ==="

# ─── Step 1: Gradle build (full, all qnnlibs) ───
echo "=== Step 1: Gradle build ($GRADLE_TASK) ==="
cd "$PROJECT_DIR"
./gradlew "$GRADLE_TASK"

# ─── Step 2: Locate APK ───
BUILD_DIR="$APP_DIR/build/outputs/apk/$FLAVOR/$BUILD_TYPE"

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

# ─── Step 3: Strip non-V81 QNN libs from APK ───
echo "=== Step 3: Stripping non-V81 QNN libs from APK ==="

APK_SIZE_BEFORE=$(du -h "$SOURCE_APK" | cut -f1)
echo "  APK before: $APK_SIZE_BEFORE"

python -c "
import zipfile, os, shutil, sys

apk = sys.argv[1]
keep = {
    'assets/qnnlibs/libQnnHtp.so',
    'assets/qnnlibs/libQnnSystem.so',
    'assets/qnnlibs/libQnnHtpV81.so',
    'assets/qnnlibs/libQnnHtpV81Skel.so',
    'assets/qnnlibs/libQnnHtpV81Stub.so',
}
tmp = apk + '.tmp'
removed = 0
with zipfile.ZipFile(apk, 'r') as zin, zipfile.ZipFile(tmp, 'w', zin.compression) as zout:
    for item in zin.infolist():
        if item.filename.startswith('assets/qnnlibs/') and item.filename not in keep:
            removed += 1
            continue
        zout.writestr(item, zin.read(item.filename))
shutil.move(tmp, apk)
print(f'  Removed {removed} QNN lib entries')
" "$SOURCE_APK"

APK_SIZE_AFTER=$(du -h "$SOURCE_APK" | cut -f1)
echo "  APK after:  $APK_SIZE_AFTER"

# ─── Step 4: Sign (release) or copy (debug) ───
OUTPUT_APK=""
if [[ "$BUILD_TYPE" == "release" ]]; then
    KEYSTORE="${KEY_STORE:-}"
    KEYSTORE_PASS="${KEY_STORE_PASSWORD:-}"
    KEY_ALIAS="${KEY_ALIAS:-pisces312}"

    if [[ -z "$KEYSTORE" ]] || [[ -z "$KEYSTORE_PASS" ]]; then
        echo "WARNING: Signing config not set (KEY_STORE / KEY_STORE_PASSWORD env vars)"
        echo "  Copying unsigned APK instead"
        OUTPUT_APK="$PROJECT_DIR/LocalDream_armv8a_${VERSION}-${FLAVOR}-sm8850-unsigned.apk"
        cp -f "$SOURCE_APK" "$OUTPUT_APK"
    else
        BUILD_TOOLS="${ANDROID_SDK_ROOT:-D:/dev/android_sdk}/build-tools/34.0.0"
        ALIGNED_APK="$BUILD_DIR/release-aligned.apk"
        OUTPUT_APK="$PROJECT_DIR/LocalDream_armv8a_${VERSION}-${FLAVOR}-sm8850-signed.apk"

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
    OUTPUT_APK="$PROJECT_DIR/LocalDream_armv8a_${VERSION}-${FLAVOR}-sm8850-debug.apk"
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
