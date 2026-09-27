#!/usr/bin/env bash
# Render packaging/INSTALL-GUIDE.html to the website's printable install guide.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
"$CHROME" --headless=new --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="${ROOT}/site/public/downloads/TimeStamp-Install-Guide.pdf" \
  "file://${ROOT}/packaging/INSTALL-GUIDE.html"
