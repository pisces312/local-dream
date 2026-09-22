#!/usr/bin/env bash
set -e
JAR=$(ls -d /d/dev/android_sdk/build-tools/*/lib/apksigner.jar | tail -1)
echo "using $JAR"
echo '=== RELEASE ==='
java -jar "$JAR" verify --print-certs \
  /d/3rd-party-projects/local-dream/app/build/outputs/apk/basic/release/LocalDream_armv8a_3.0.0-alpha.3.apk
echo '=== DEBUG ==='
java -jar "$JAR" verify --print-certs \
  /d/3rd-party-projects/local-dream/app/build/outputs/apk/basic/debug/LocalDream_armv8a_3.0.0-alpha.3_debug.apk
