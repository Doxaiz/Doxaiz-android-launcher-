#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-/tmp/android-sdk}"
BUILD_TOOLS="${SDK_DIR}/build-tools/android-14"
ANDROID_JAR="${SDK_DIR}/platforms/android-34/android.jar"

WORK_DIR="/tmp/doxaiz-build"
RES_DIR="${PROJECT_DIR}/app/src/main/res"
JAVA_DIR="${PROJECT_DIR}/app/src/main/java"
MANIFEST="${PROJECT_DIR}/app/src/main/AndroidManifest.xml"
# Supply signing material externally; never store a private signing key in Git.
: "${DOXAIZ_KEYSTORE:?Set DOXAIZ_KEYSTORE to a private keystore path}"
: "${DOXAIZ_KEYSTORE_PASSWORD:?Set DOXAIZ_KEYSTORE_PASSWORD}"
: "${DOXAIZ_KEY_PASSWORD:?Set DOXAIZ_KEY_PASSWORD}"
KEYSTORE="${DOXAIZ_KEYSTORE}"
KEY_ALIAS="${DOXAIZ_KEY_ALIAS:-doxaiz}"
[ -f "${KEYSTORE}" ] || { echo "Keystore not found" >&2; exit 1; }
OUT_APK="${PROJECT_DIR}/Doxaiz-Launcher.apk"

JAVA_BIN_DIR="$(dirname "$(readlink -f "$(command -v java)")")"
export PATH="${JAVA_BIN_DIR}:${PATH}"

rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}/gen" "${WORK_DIR}/classes" "${WORK_DIR}/dex"

echo "[1/6] Компиляция ресурсов (aapt2 compile)..."
"${BUILD_TOOLS}/aapt2" compile --dir "${RES_DIR}" -o "${WORK_DIR}/res.zip"

echo "[2/6] Линковка ресурсов и R.java (aapt2 link)..."
"${BUILD_TOOLS}/aapt2" link \
    -o "${WORK_DIR}/unsigned.apk" \
    -I "${ANDROID_JAR}" \
    --manifest "${MANIFEST}" \
    --java "${WORK_DIR}/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    --version-code 38 \
    --version-name "5.5-test" \
    --auto-add-overlay \
    "${WORK_DIR}/res.zip"

echo "[3/6] Компиляция Java-кода (javac)..."
find "${JAVA_DIR}" "${WORK_DIR}/gen" -name "*.java" > "${WORK_DIR}/sources.txt"
javac \
    -source 1.8 -target 1.8 \
    -encoding UTF-8 \
    -Xlint:all,-deprecation,-rawtypes \
    -bootclasspath "${ANDROID_JAR}:${BUILD_TOOLS}/core-lambda-stubs.jar" \
    -classpath "${ANDROID_JAR}" \
    -d "${WORK_DIR}/classes" \
    @"${WORK_DIR}/sources.txt"

echo "[4/6] Сборка classes.dex (d8)..."
CLASS_FILES=()
while IFS= read -r -d '' f; do
    CLASS_FILES+=("$f")
done < <(find "${WORK_DIR}/classes" -name "*.class" -print0)

"${BUILD_TOOLS}/d8" \
    --release \
    --min-api 26 \
    --lib "${ANDROID_JAR}" \
    --output "${WORK_DIR}/dex" \
    "${CLASS_FILES[@]}"

echo "[5/6] Упаковка и выравнивание APK (zip + zipalign)..."
cp "${WORK_DIR}/unsigned.apk" "${WORK_DIR}/unaligned.apk"
(cd "${WORK_DIR}/dex" && zip -q -u "${WORK_DIR}/unaligned.apk" classes.dex)
"${BUILD_TOOLS}/zipalign" -f -p 4 "${WORK_DIR}/unaligned.apk" "${WORK_DIR}/aligned.apk"

echo "[6/6] Подпись APK (apksigner v1+v2+v3)..."
"${BUILD_TOOLS}/apksigner" sign \
    --ks "${KEYSTORE}" \
    --ks-key-alias "${KEY_ALIAS}" \
    --ks-pass env:DOXAIZ_KEYSTORE_PASSWORD \
    --key-pass env:DOXAIZ_KEY_PASSWORD \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --v4-signing-enabled false \
    --out "${OUT_APK}" \
    "${WORK_DIR}/aligned.apk"

echo "[Проверка подписи и манифеста]"
"${BUILD_TOOLS}/apksigner" verify --min-sdk-version 21 --verbose "${OUT_APK}"
ls -lh "${OUT_APK}"
