#!/bin/bash
set -euo pipefail

PACKAGE_DIR="$(cd "$(dirname "$0")" && pwd)"
QUPATH_USER_DIR="${QUPATH_USER_DIR:-${HOME}/QuPath/v0.6}"
EXTENSIONS_DIR="${QUPATH_USER_DIR}/extensions"
SUPPORT_DIR="${QUPATH_USER_DIR}/timestamp"
RUNTIME_DIR="${SUPPORT_DIR}/runtime"
VENV_DIR="${RUNTIME_DIR}/.venv"
TOOLS_DIR="${SUPPORT_DIR}/tools"
MODEL_CACHE_DIR="${SUPPORT_DIR}/model-cache"
UV_BIN="${TOOLS_DIR}/uv"
UV_VERSION="0.12.5"
REQUIREMENTS_FILE="${PACKAGE_DIR}/requirements-doctor.txt"
CHECKSUMS_FILE="${PACKAGE_DIR}/CHECKSUMS-SHA256.txt"
HELPER_FILE="${PACKAGE_DIR}/live_whisper_demo.py"

finish_with_error() {
  local exit_code=$?
  echo
  echo "TimeStamp installation did not finish."
  echo "You can copy this window's message when asking for support."
  if [[ -t 0 ]]; then
    read -r -p "Press Return to close..." _ || true
  fi
  exit "$exit_code"
}
trap finish_with_error ERR

echo "TimeStamp doctor installation"
echo "QuPath folder: ${QUPATH_USER_DIR}"
echo
echo "QuPath 0.6 must be opened once to complete its first-time setup, then closed."
echo

if pgrep -f '/QuPath[^/]*/Contents/MacOS/QuPath' >/dev/null 2>&1; then
  echo "Please close QuPath completely, then run this installer again."
  exit 1
fi

if [[ ! -f "$REQUIREMENTS_FILE" ]]; then
  echo "Missing requirements file: ${REQUIREMENTS_FILE}"
  exit 1
fi
if [[ ! -f "$CHECKSUMS_FILE" ]]; then
  echo "Missing package checksum file: ${CHECKSUMS_FILE}"
  exit 1
fi
if [[ ! -f "$HELPER_FILE" ]]; then
  echo "Missing microphone helper: ${HELPER_FILE}"
  exit 1
fi

echo "Verifying the TimeStamp package..."
(
  cd "$PACKAGE_DIR"
  shasum -a 256 -c "$(basename "$CHECKSUMS_FILE")"
)

JAR_FILE=""
for candidate in "${PACKAGE_DIR}"/TimeStamp-*.jar; do
  case "$(basename "$candidate")" in
    *-javadoc.jar|*-sources.jar) continue ;;
  esac
  if [[ -f "$candidate" ]]; then
    JAR_FILE="$candidate"
    break
  fi
done
if [[ -z "$JAR_FILE" ]]; then
  echo "The TimeStamp extension JAR is missing from this package."
  exit 1
fi

mkdir -p "$EXTENSIONS_DIR" "$RUNTIME_DIR" "$TOOLS_DIR" "$MODEL_CACHE_DIR"

if [[ ! -x "$UV_BIN" ]]; then
  case "$(uname -m)" in
    arm64)
      UV_PLATFORM="aarch64-apple-darwin"
      UV_SHA256="5bb0e5fe008a773c3dbcb97ff79cd89e1241464fe9d2f986d52ad8f1b037bd62"
      ;;
    x86_64)
      UV_PLATFORM="x86_64-apple-darwin"
      UV_SHA256="b3b2137477cf96c9686ebfb71524614cec780c673fd73e59bce099aef02e70e8"
      ;;
    *)
      echo "Unsupported Mac architecture: $(uname -m)"
      exit 1
      ;;
  esac
  TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/timestamp-uv.XXXXXX")"
  trap 'rm -rf "$TEMP_DIR"' EXIT
  echo "Downloading the private TimeStamp setup tool..."
  curl --fail --location --silent --show-error \
    "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-${UV_PLATFORM}.tar.gz" \
    --output "${TEMP_DIR}/uv.tar.gz"
  printf '%s  %s\n' "$UV_SHA256" "${TEMP_DIR}/uv.tar.gz" | shasum -a 256 -c -
  tar -xzf "${TEMP_DIR}/uv.tar.gz" -C "$TEMP_DIR"
  DOWNLOADED_UV="$(find "$TEMP_DIR" -type f -name uv -perm -u+x -print -quit)"
  if [[ -z "$DOWNLOADED_UV" ]]; then
    echo "The downloaded setup tool did not contain the expected uv executable."
    exit 1
  fi
  cp "$DOWNLOADED_UV" "${UV_BIN}.new"
  chmod 755 "${UV_BIN}.new"
  mv -f "${UV_BIN}.new" "$UV_BIN"
  rm -rf "$TEMP_DIR"
  trap - EXIT
fi

export UV_CACHE_DIR="${SUPPORT_DIR}/download-cache"
export UV_PYTHON_INSTALL_DIR="${RUNTIME_DIR}/python"
export HF_HOME="$MODEL_CACHE_DIR"

echo "Preparing the private Python runtime..."
PYTHON_BIN="${VENV_DIR}/bin/python"
if [[ ! -x "$PYTHON_BIN" ]]; then
  "$UV_BIN" venv --python 3.12 --managed-python "$VENV_DIR"
fi
echo "Installing the recorder and speech-to-text libraries..."
"$UV_BIN" pip install --python "$PYTHON_BIN" --requirements "$REQUIREMENTS_FILE"

if [[ "${TIMESTAMP_SKIP_MODEL_DOWNLOAD:-0}" != "1" ]]; then
  echo "Downloading the live transcription model..."
  "$PYTHON_BIN" -c 'from huggingface_hub import snapshot_download; snapshot_download("Systran/faster-whisper-small.en")'
  echo "Downloading the final high-accuracy model (this is the largest download)..."
  "$PYTHON_BIN" -c 'from huggingface_hub import snapshot_download; snapshot_download("Systran/faster-whisper-large-v3")'
fi

echo "Verifying microphone and transcription support..."
"$PYTHON_BIN" -c 'import faster_whisper, numpy, sounddevice; devices=sounddevice.query_devices(); print(f"Recorder ready; {len(devices)} audio device(s) detected")'
echo "Testing the microphone for 3 seconds. Speak normally now..."
if ! "$PYTHON_BIN" "$HELPER_FILE" --check-audio --check-seconds 3; then
  echo "Warning: the microphone test could not open an input. Installation will finish; use Test microphone in QuPath after checking macOS permissions."
fi

INSTALL_TARGET="${EXTENSIONS_DIR}/$(basename "$JAR_FILE")"
cp "$JAR_FILE" "${INSTALL_TARGET}.new"
for old_jar in "${EXTENSIONS_DIR}"/TimeStamp-*.jar; do
  if [[ -f "$old_jar" && "$old_jar" != "$INSTALL_TARGET" ]]; then
    rm -f "$old_jar"
  fi
done
mv -f "${INSTALL_TARGET}.new" "$INSTALL_TARGET"

cat > "${SUPPORT_DIR}/doctor-runtime.json" <<EOF
{
  "runtime": "${PYTHON_BIN}",
  "modelCache": "${MODEL_CACHE_DIR}",
  "liveModel": "Systran/faster-whisper-small.en",
  "finalModel": "Systran/faster-whisper-large-v3",
  "installedAtUtc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

echo
echo "TimeStamp is ready for the doctor."
echo "1. Open QuPath 0.6."
echo "2. Allow microphone access if macOS asks."
echo "3. Open Extensions > TimeStamp Extension > Open Clinical Session Recorder."
echo "4. Click Start Recording."
echo
echo "Transcription stays on this Mac after the model download."
if [[ -t 0 ]]; then
  read -r -p "Press Return to close..." _ || true
fi
