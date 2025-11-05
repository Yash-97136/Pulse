#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ -f ingest.pid ]; then
  PID=$(cat ingest.pid || true)
  if [ -n "${PID}" ] && ps -p "$PID" >/dev/null 2>&1; then
    echo "Stopping Reddit ingestion (PID=$PID)..."
    kill "$PID" || true
    # Give it a moment to exit gracefully
    sleep 2
  fi
  rm -f ingest.pid
fi

# Fallback: ensure no stray process
if pgrep -f 'python.*reddit_ingest.py' >/dev/null 2>&1; then
  echo "Killing stray reddit_ingest.py processes..."
  pkill -f 'python.*reddit_ingest.py' || true
fi

echo "Stopped."
