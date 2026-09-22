#!/usr/bin/env bash
set -euo pipefail

# Prepare a demo session folder with consistent output names.
# Usage:
#   ./scripts/prepare_demo.sh [demo_name] [base_dir]
# Example:
#   ./scripts/prepare_demo.sh liver-case1 ./demo-output

DEMO_NAME="${1:-demo}"
BASE_DIR="${2:-./demo-output}"
STAMP="$(date +%Y%m%d_%H%M%S)"
SESSION_ID="${STAMP}_${DEMO_NAME}"
SESSION_DIR="${BASE_DIR}/${SESSION_ID}"

mkdir -p "${SESSION_DIR}"/{events,cursor,video,notes}

EVENT_JSON="${SESSION_DIR}/events/${SESSION_ID}_event.json"
CURSOR_JSON="${SESSION_DIR}/cursor/${SESSION_ID}_cursor.json"
VIDEO_MP4="${SESSION_DIR}/video/${SESSION_ID}_video.mp4"
TRANSCRIPT_TXT="${SESSION_DIR}/video/${SESSION_ID}_transcript.txt"
MANIFEST_JSON="${SESSION_DIR}/${SESSION_ID}_recording_manifest.json"

cat > "${SESSION_DIR}/notes/recording-plan.txt" <<EOF
Demo session: ${SESSION_ID}

Command-line helper demo:
1) Start recording video/audio.
2) Start the transcript helper using the command printed below.
3) Perform actions in QuPath.
4) Stop the helper to generate the final transcript.

Normal QuPath extension workflow does not require this prepared folder. Click
Start Recording first, then choose the parent folder and session name only after
clicking Save Transcript & Timestamps.

Save exports as:
- Event log JSON: ${EVENT_JSON}
- Cursor log JSON: ${CURSOR_JSON}
- Video: ${VIDEO_MP4}
- Transcript: ${TRANSCRIPT_TXT}
- Completion manifest: ${MANIFEST_JSON}
EOF

touch "${EVENT_JSON}" "${CURSOR_JSON}" "${TRANSCRIPT_TXT}"

cat <<EOF
Prepared demo session:
  ${SESSION_DIR}

Expected outputs:
  Event log : ${EVENT_JSON}
  Cursor log: ${CURSOR_JSON}
  Video file: ${VIDEO_MP4}
  Transcript: ${TRANSCRIPT_TXT}
  Manifest  : ${MANIFEST_JSON}

Next:
  1) Run screen recorder and save to the video path above.
  2) Start live transcript with:
     ./scripts/start_live_transcript.sh "${SESSION_DIR}" large-v3 en
  3) For the extension workflow, open the TimeStamp Monitor and click Start Recording without selecting a folder.
EOF
