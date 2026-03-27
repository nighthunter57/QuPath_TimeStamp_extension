#!/usr/bin/env bash
set -euo pipefail

# Start live Whisper transcription and save transcript inside a demo session folder.
#
# Usage:
#   ./scripts/start_live_transcript.sh <session_dir> [model] [language]
# Example:
#   ./scripts/start_live_transcript.sh ./demo-output/20260327_101500_caseA small en

usage() {
  cat <<EOF
Usage: $0 <session_dir> [model] [language]

Starts live microphone transcription for an existing demo session directory.

Arguments:
  session_dir   Existing demo session folder created by prepare_demo.sh
  model         Optional faster-whisper model (default: small)
  language      Optional language code, or auto (default: en)

Example:
  $0 ./demo-output/20260327_101500_caseA small en
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 || $# -gt 3 ]]; then
  usage >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VENV_DIR="${REPO_ROOT}/.venv-whisper"
PYTHON_SCRIPT="${SCRIPT_DIR}/live_whisper_demo.py"

SESSION_DIR="$1"
MODEL="${2:-small}"
LANGUAGE="${3:-en}"
SESSION_ID="$(basename "$SESSION_DIR")"
TRANSCRIPT_PATH="$SESSION_DIR/video/${SESSION_ID}_transcript.txt"
RUN_COMMAND="./scripts/start_live_transcript.sh \"$SESSION_DIR\" \"$MODEL\" \"$LANGUAGE\""

if [[ ! -d "$SESSION_DIR" ]]; then
  echo "Error: session directory not found: $SESSION_DIR" >&2
  echo "Create a session first with:" >&2
  echo "  ./scripts/prepare_demo.sh demo-name" >&2
  exit 1
fi

if [[ ! -f "$PYTHON_SCRIPT" ]]; then
  echo "Error: transcription script not found: $PYTHON_SCRIPT" >&2
  exit 1
fi

if [[ ! -f "${VENV_DIR}/bin/activate" ]]; then
  echo "Error: Python venv not found: ${VENV_DIR}" >&2
  echo "Set it up from the repo root with:" >&2
  echo "  python3 -m venv .venv-whisper" >&2
  echo "  source .venv-whisper/bin/activate" >&2
  echo "  python -m pip install --upgrade pip" >&2
  echo "  python -m pip install faster-whisper sounddevice numpy" >&2
  exit 1
fi

mkdir -p "$SESSION_DIR/video"

echo "Live transcript launcher"
echo "  Model       : $MODEL"
echo "  Language    : $LANGUAGE"
echo "  Output file : $TRANSCRIPT_PATH"
echo "  Run command : $RUN_COMMAND"
echo
echo "Transcript is being written to:"
echo "  $TRANSCRIPT_PATH"
echo

cd "$REPO_ROOT"
source "${VENV_DIR}/bin/activate"

python "$PYTHON_SCRIPT" \
  --output "$TRANSCRIPT_PATH" \
  --model "$MODEL" \
  --language "$LANGUAGE"
