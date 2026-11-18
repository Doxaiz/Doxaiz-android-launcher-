#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-/tmp/android-sdk}"

# Build Tools в Android SDK раскладываются по версии (34.0.0, 35.0.0, ...), а не по
# схеме platforms (android-34). Разрешаем переопределение и подхватываем последнюю
# установленную версию, чтобы скрипт не падал на несуществующем каталоге.
BUILD_TOOLS_VERSION="${DOXAIZ_BUILD_TOOLS_VERSION:-34.0.0}"
if [ ! -d "${SDK_DIR}/build-tools/${BUILD_TOOLS_VERSION}" ]; then
    for candidate in "${SDK_DIR}"/build-tools/*; do
        [ -d "$candidate" ] || continue
        BUILD_TOOLS_VERSION="$(basename "$candidate")"
    done
fi
BUILD_TOOLS="${SDK_DIR}/build-tools/${BUILD_TOOLS_VERSION}"

# Единственный источник истины по версии — app/build.gradle (namespace для aapt2 — package в манифесте).
GRADLE_FILE="${PROJECT_DIR}/app/build.gradle"
MIN_SDK="$(sed -n 's/.*minSdk[[:space:]]\+\([0-9]\+\).*/\1/p' "$GRADLE_FILE" | head -1)"
TARGET_SDK="$(sed -n 's/.*targetSdk[[:space:]]\+\([0-9]\+\).*/\1/p' "$GRADLE_FILE" | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode[[:space:]]\+\([0-9]\+\).*/\1/p' "$GRADLE_FILE" | head -1)"
VERSION_NAME="$(sed -n 's/.*versionName[[:space:]]\+"\(.*\)".*/\1/p' "$GRADLE_FILE" | head -1)"
: "${MIN_SDK:?Не удалось прочитать minSdk из app/build.gradle}"
: "${TARGET_SDK:?Не удалось прочитать targetSdk из app/build.gradle}"
: "${VERSION_CODE:?Не удалось прочитать versionCode из app/build.gradle}"
: "${VERSION_NAME:?Не удалось прочитать versionName из app/build.gradle}"

ANDROID_JAR="${SDK_DIR}/platforms/android-${TARGET_SDK}/android.jar"
[ -x "${BUILD_TOOLS}/aapt2" ] || { echo "Build Tools не найдены: ${BUILD_TOOLS}" >&2; exit 1; }
[ -f "${ANDROID_JAR}" ] || { echo "android.jar не найден: ${ANDROID_JAR}" >&2; exit 1; }

echo "SDK: ${SDK_DIR} | Build Tools: ${BUILD_TOOLS_VERSION} | minSdk ${MIN_SDK} / targetSdk ${TARGET_SDK} | ${VERSION_NAME} (${VERSION_CODE})"

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
    --min-sdk-version "${MIN_SDK}" \
    --target-sdk-version "${TARGET_SDK}" \
    --version-code "${VERSION_CODE}" \
    --version-name "${VERSION_NAME}" \
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
    --min-api "${MIN_SDK}" \
    --lib "${ANDROID_JAR}" \
    --output "${WORK_DIR}/dex" \
    "${CLASS_FILES[@]}"

echo "[5/6] Упаковка и выравнивание APK (zip + zipalign)..."
cp "${WORK_DIR}/unsigned.apk" "${WORK_DIR}/unaligned.apk"
(cd "${WORK_DIR}/dex" && zip -q -u "${WORK_DIR}/unaligned.apk" classes.dex)
"${BUILD_TOOLS}/zipalign" -f -p 4 "${WORK_DIR}/unaligned.apk" "${WORK_DIR}/aligned.apk"

echo "[6/6] Подпись APK (apksigner v2+v3; v1 не нужен при minSdk ${MIN_SDK})..."
"${BUILD_TOOLS}/apksigner" sign \
    --ks "${KEYSTORE}" \
    --ks-key-alias "${KEY_ALIAS}" \
    --ks-pass env:DOXAIZ_KEYSTORE_PASSWORD \
    --key-pass env:DOXAIZ_KEY_PASSWORD \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --v4-signing-enabled false \
    --out "${OUT_APK}" \
    "${WORK_DIR}/aligned.apk"

echo "[Проверка подписи и манифеста]"
"${BUILD_TOOLS}/apksigner" verify --min-sdk-version "${MIN_SDK}" --verbose "${OUT_APK}"

# SHA-256 рядом с APK: человеку нужно с чем-то сверять скачанный файл, а не
# доверять размеру. Формат тот же, что у релиза CI (sha256sum: «хеш  имя»).
if command -v sha256sum >/dev/null 2>&1; then
    ( cd "$(dirname "${OUT_APK}")" && sha256sum "$(basename "${OUT_APK}")" > "${OUT_APK}.sha256" )
    echo "[SHA-256]"
    cat "${OUT_APK}.sha256"
else
    echo "sha256sum не найден — хеш не записан (на APK это не влияет)" >&2
fi

ls -lh "${OUT_APK}"
