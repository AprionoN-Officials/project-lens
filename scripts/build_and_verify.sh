#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

echo "=================================================================="
echo " Project Asisten (Project Lens v0.1) — Build & Verification Runner"
echo "=================================================================="

echo ""
echo "[1/3] Compiling & running standalone JVM Core Logic & XML Verification Suite (OpenJDK 17)..."
mkdir -p build/jvm-verify
javac -d build/jvm-verify app/jvm-verification/VerifyCoreLogic.java
java -cp build/jvm-verify VerifyCoreLogic

echo ""
echo "[2/3] Auditing source tree completeness..."
KT_COUNT=$(find app/src -name "*.kt" | wc -l)
XML_COUNT=$(find app/src -name "*.xml" | wc -l)
echo "  Kotlin source files : ${KT_COUNT}"
echo "  XML resource files  : ${XML_COUNT}"

echo ""
echo "[3/3] Checking Android SDK & Gradle environment for APK assembly..."
if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME:-}" ]; then
    echo "  ANDROID_HOME detected at ${ANDROID_HOME}. Running Gradle assembleDebug..."
    gradle assembleDebug
else
    echo "  NOTE: ANDROID_HOME / Android SDK platform tools (aapt2, d8, android.jar)"
    echo "  are not pre-installed in this sandboxed container, and outbound downloads"
    echo "  to dl.google.com / maven.google.com / services.gradle.org return HTTP 403"
    echo "  due to the sandbox proxy policy."
    echo "  To build the installable APK on a machine with Android Studio / Android SDK:"
    echo "    export ANDROID_HOME=/path/to/Android/Sdk"
    echo "    ./gradlew assembleDebug"
    echo "  Output APK path: app/build/outputs/apk/debug/app-debug.apk"
fi
