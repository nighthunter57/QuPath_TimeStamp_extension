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

cat > "${SESSION_DIR}/notes/recording-plan.txt" <<EOF
Demo session: ${SESSION_ID}

During recording (QuPath + screen recorder):
1) Start recording video/audio.
2) In QuPath TimeStamp Monitor, click Start Recording.
3) Perform actions: zoom, pan, annotate.
4) If take is bad: stop video, click Clear Events, restart both.
5) End take: stop video and export JSON logs.

Save exports as:
- Event log JSON: ${EVENT_JSON}
- Cursor log JSON: ${CURSOR_JSON}
- Video: ${VIDEO_MP4}
- Transcript: ${TRANSCRIPT_TXT}
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

Next:
  1) Run screen recorder and save to the video path above.
  2) Start live transcript with:
     ./scripts/start_live_transcript.sh "${SESSION_DIR}" small en
  3) In QuPath, export event log JSON and mouse movement JSON to the paths above.
EOF
