#!/usr/bin/env bash
set -euo pipefail

PACKAGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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
  echo "Copy this terminal message when asking for support."
  exit "$exit_code"
}
trap finish_with_error ERR

echo "TimeStamp doctor installation for Linux"
echo "QuPath folder: ${QUPATH_USER_DIR}"
echo
echo "QuPath 0.6 must be opened once to complete its first-time setup, then closed."
echo

if pgrep -x QuPath >/dev/null 2>&1; then
  echo "Please close QuPath completely, then run this installer again."
  exit 1
fi

for required_file in "$REQUIREMENTS_FILE" "$CHECKSUMS_FILE" "$HELPER_FILE"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Missing package file: ${required_file}"
    exit 1
  fi
done

echo "Verifying the TimeStamp package..."
(
  cd "$PACKAGE_DIR"
  sha256sum -c "$(basename "$CHECKSUMS_FILE")"
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
    x86_64|amd64)
      UV_PLATFORM="x86_64-unknown-linux-gnu"
      UV_SHA256="68a509da24b06b4223a1c0175fb5eb5bc79342b76cbeff0cfe51ac3f5b17b6b2"
      ;;
    aarch64|arm64)
      UV_PLATFORM="aarch64-unknown-linux-gnu"
      UV_SHA256="9bf43b4d1a07665bf64d4c4e710930b382321a785e0eb10aac07f46471f86a31"
      ;;
    *)
      echo "Unsupported Linux architecture: $(uname -m)"
      exit 1
      ;;
  esac
  command -v curl >/dev/null 2>&1 || {
    echo "curl is required. Ask IT to install curl, then run this installer again."
    exit 1
  }
  TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/timestamp-uv.XXXXXX")"
  trap 'rm -rf "$TEMP_DIR"' EXIT
  echo "Downloading the private TimeStamp setup tool..."
  curl --fail --location --silent --show-error \
    "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-${UV_PLATFORM}.tar.gz" \
    --output "${TEMP_DIR}/uv.tar.gz"
  printf '%s  %s\n' "$UV_SHA256" "${TEMP_DIR}/uv.tar.gz" | sha256sum -c -
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

if ! "$PYTHON_BIN" -c 'import sounddevice; sounddevice.get_portaudio_version()' >/dev/null 2>&1; then
  echo "Linux needs the PortAudio system library for microphone recording."
  ADMIN_COMMAND=()
  if [[ "$EUID" -ne 0 ]]; then
    if command -v sudo >/dev/null 2>&1; then
      ADMIN_COMMAND=(sudo)
    else
      echo "Administrator access is required to install PortAudio. Ask IT for help."
      exit 1
    fi
  fi
  if command -v apt-get >/dev/null 2>&1; then
    "${ADMIN_COMMAND[@]}" apt-get update
    "${ADMIN_COMMAND[@]}" apt-get install -y libportaudio2
  elif command -v dnf >/dev/null 2>&1; then
    "${ADMIN_COMMAND[@]}" dnf install -y portaudio
  elif command -v zypper >/dev/null 2>&1; then
    "${ADMIN_COMMAND[@]}" zypper --non-interactive install portaudio
  elif command -v pacman >/dev/null 2>&1; then
    "${ADMIN_COMMAND[@]}" pacman -S --needed portaudio
  else
    echo "Ask IT to install PortAudio, then run this installer again."
    exit 1
  fi
  "$PYTHON_BIN" -c 'import sounddevice; sounddevice.get_portaudio_version()' >/dev/null
fi

if [[ "${TIMESTAMP_SKIP_MODEL_DOWNLOAD:-0}" != "1" ]]; then
  echo "Downloading the live transcription model..."
  "$PYTHON_BIN" -c 'from huggingface_hub import snapshot_download; snapshot_download("Systran/faster-whisper-small.en")'
  echo "Downloading the final high-accuracy model (this is the largest download)..."
  "$PYTHON_BIN" -c 'from huggingface_hub import snapshot_download; snapshot_download("Systran/faster-whisper-large-v3")'
fi

echo "Verifying microphone and transcription support..."
"$PYTHON_BIN" -c 'import faster_whisper, numpy, sounddevice; devices=sounddevice.query_devices(); print(f"Recorder ready; {len(devices)} audio device(s) detected")'
if [[ "${TIMESTAMP_SKIP_AUDIO_CHECK:-0}" != "1" ]]; then
  echo "Testing the microphone for 3 seconds. Speak normally now..."
  if ! "$PYTHON_BIN" "$HELPER_FILE" --check-audio --check-seconds 3; then
    echo "Warning: the microphone test could not open an input. Installation will finish; use Test microphone in QuPath after checking OS permissions."
  fi
fi

INSTALL_TARGET="${EXTENSIONS_DIR}/$(basename "$JAR_FILE")"
BACKUP_DIR="${SUPPORT_DIR}/extension-backups/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$BACKUP_DIR"
for old_jar in "${EXTENSIONS_DIR}"/TimeStamp-*.jar; do
  if [[ -f "$old_jar" ]]; then cp "$old_jar" "$BACKUP_DIR/"; fi
done
cp "$JAR_FILE" "${INSTALL_TARGET}.new"
for old_jar in "${EXTENSIONS_DIR}"/TimeStamp-*.jar; do
  if [[ -f "$old_jar" && "$old_jar" != "$INSTALL_TARGET" ]]; then
    rm -f "$old_jar"
  fi
done
mv -f "${INSTALL_TARGET}.new" "$INSTALL_TARGET"

TIMESTAMP_SUPPORT_DIR="$SUPPORT_DIR" TIMESTAMP_PYTHON_BIN="$PYTHON_BIN" \
  "$PYTHON_BIN" -c 'import json, os, pathlib; from datetime import datetime, timezone; support=pathlib.Path(os.environ["TIMESTAMP_SUPPORT_DIR"]); data={"runtime":os.environ["TIMESTAMP_PYTHON_BIN"],"modelCache":os.environ["HF_HOME"],"liveModel":"Systran/faster-whisper-small.en","finalModel":"Systran/faster-whisper-large-v3","installedAtUtc":datetime.now(timezone.utc).isoformat().replace("+00:00","Z")}; (support/"doctor-runtime.json").write_text(json.dumps(data, indent=2)+"\n", encoding="utf-8")'

echo
echo "TimeStamp is ready for the doctor."
echo "Open QuPath 0.6, allow microphone access, then open the TimeStamp monitor."
