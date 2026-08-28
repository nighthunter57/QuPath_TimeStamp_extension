#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VERSION="${1:-0.1.0-SNAPSHOT}"
PACKAGE_NAME="TimeStamp-Doctor-${VERSION}"
STAGING_ROOT="${REPO_ROOT}/build/doctor-package"
PACKAGE_DIR="${STAGING_ROOT}/${PACKAGE_NAME}"
DISTRIBUTION_DIR="${REPO_ROOT}/build/distributions"

cd "$REPO_ROOT"
./gradlew clean build -PtimestampVersion="$VERSION"

rm -rf "$STAGING_ROOT"
mkdir -p "$PACKAGE_DIR" "$DISTRIBUTION_DIR"
cp "build/libs/TimeStamp-${VERSION}.jar" "$PACKAGE_DIR/"
cp "requirements-doctor.txt" "$PACKAGE_DIR/"
cp "scripts/live_whisper_demo.py" "$PACKAGE_DIR/"
cp "packaging/macos/Install TimeStamp.command" "$PACKAGE_DIR/"
cp "packaging/linux/Install TimeStamp.sh" "$PACKAGE_DIR/Install TimeStamp on Linux.sh"
cp "packaging/windows/Install TimeStamp on Windows.bat" "$PACKAGE_DIR/"
cp "packaging/windows/Install-TimeStamp.ps1" "$PACKAGE_DIR/"
cp "packaging/DOCTOR-INSTALL.txt" "$PACKAGE_DIR/"
chmod 755 "$PACKAGE_DIR/Install TimeStamp.command" "$PACKAGE_DIR/Install TimeStamp on Linux.sh"

(
  cd "$PACKAGE_DIR"
  shasum -a 256 "TimeStamp-${VERSION}.jar" "requirements-doctor.txt" \
    "live_whisper_demo.py" > CHECKSUMS-SHA256.txt
)

rm -f "${DISTRIBUTION_DIR}/${PACKAGE_NAME}.zip"
(
  cd "$STAGING_ROOT"
  zip -qry "${DISTRIBUTION_DIR}/${PACKAGE_NAME}.zip" "$PACKAGE_NAME"
)

echo "Doctor package created:"
echo "  ${DISTRIBUTION_DIR}/${PACKAGE_NAME}.zip"
