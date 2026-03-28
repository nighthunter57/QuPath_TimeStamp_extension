#!/usr/bin/env bash
set -euo pipefail

# Start live Whisper transcription and save transcript inside a demo session folder.
#
# Usage:
#   ./scripts/start_live_transcript.sh <session_dir> [model] [language] [options]
# Example:
#   ./scripts/start_live_transcript.sh ./demo-output/20260327_101500_caseA large-v3 en --chunk-seconds 3.5 --beam-size 5 --best-of 5 --previous-text true

usage() {
  cat <<EOF
Usage: $0 <session_dir> [model] [language] [options]

Starts live microphone transcription for an existing demo session directory.

Arguments:
  session_dir   Existing demo session folder created by prepare_demo.sh
  model         Optional faster-whisper model (default: large-v3)
  language      Optional language code, or auto (default: en)
  --chunk-seconds N   Optional chunk size in seconds
  --compute-type T    Optional compute type
  --beam-size N       Optional beam size
  --best-of N         Optional best_of value
  --previous-text V   Optional true/false for previous text context

Example:
  $0 ./demo-output/20260327_101500_caseA large-v3 en --chunk-seconds 3.5 --beam-size 5 --best-of 5 --previous-text true
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VENV_DIR="${REPO_ROOT}/.venv-whisper"
PYTHON_SCRIPT="${SCRIPT_DIR}/live_whisper_demo.py"

SESSION_DIR="$1"
shift || true
MODEL="${1:-large-v3}"
if [[ $# -gt 0 ]]; then
  shift
fi
LANGUAGE="${1:-en}"
if [[ $# -gt 0 ]]; then
  shift
fi
SESSION_ID="$(basename "$SESSION_DIR")"
TRANSCRIPT_PATH="$SESSION_DIR/video/${SESSION_ID}_transcript.txt"
RUN_COMMAND="./scripts/start_live_transcript.sh \"$SESSION_DIR\" \"$MODEL\" \"$LANGUAGE\""
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --chunk-seconds|--compute-type|--beam-size|--best-of|--previous-text)
      if [[ $# -lt 2 ]]; then
        echo "Error: missing value for $1" >&2
        exit 1
      fi
      EXTRA_ARGS+=("$1" "$2")
      shift 2
      ;;
    *)
      echo "Error: unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

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
if [[ ${#EXTRA_ARGS[@]} -gt 0 ]]; then
  echo "  Extra args  : ${EXTRA_ARGS[*]}"
fi
echo
echo "Transcript is being written to:"
echo "  $TRANSCRIPT_PATH"
echo

cd "$REPO_ROOT"
source "${VENV_DIR}/bin/activate"

python "$PYTHON_SCRIPT" \
  --output "$TRANSCRIPT_PATH" \
  --model "$MODEL" \
  --language "$LANGUAGE" \
  "${EXTRA_ARGS[@]}"
