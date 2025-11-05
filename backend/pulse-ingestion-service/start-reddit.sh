#!/usr/bin/env bash
set -euo pipefail

# Start Reddit ingestion with sane defaults and logging to ingest.log
# - The Python script reads .env itself via python-dotenv; we DO NOT source .env here
# - Creates a local Python venv in .venv
# - Writes PID to ingest.pid

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Defaults (can be overridden via env or flags)
# Defaults (Python will also read .env for these; flags here override .env)
RPS_DEFAULT=4
BACKFILL_DEFAULT=60
SUBREDDITS_DEFAULT=

usage() {
  cat <<EOF
Usage: $(basename "$0") [--rps N] [--backfill-minutes M] [--subreddits "a+b+c"] [--force]

Options:
  --rps N               Messages per second throttle (default: ${RPS_DEFAULT})
  --backfill-minutes M  Backfill recent posts before streaming (default: ${BACKFILL_DEFAULT})
  --subreddits LIST     Subreddits to monitor, '+' joined (default: from .env or script default)
  --force               Stop existing process if found and start a new one

Notes:
- Credentials are read from .env: REDDIT_CLIENT_ID, REDDIT_CLIENT_SECRET, REDDIT_USER_AGENT
- Redis settings via .env: SINK=redis, REDIS_HOST, REDIS_PORT, REDIS_DB
- Logs are written to ingest.log in this directory
EOF
}

FORCE=0
RPS_VAL="$RPS_DEFAULT"
BACKFILL_VAL="$BACKFILL_DEFAULT"
SUBREDDITS_VAL="$SUBREDDITS_DEFAULT"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rps)
      RPS_VAL="$2"; shift 2;;
    --backfill-minutes)
      BACKFILL_VAL="$2"; shift 2;;
    --subreddits)
      SUBREDDITS_VAL="$2"; shift 2;;
    --force)
      FORCE=1; shift;;
    -h|--help)
      usage; exit 0;;
    *)
      echo "Unknown arg: $1" >&2; usage; exit 2;;
  esac
done

# Ensure venv
if [ ! -d .venv ]; then
  python3 -m venv .venv
fi

# Activate and install deps
source .venv/bin/activate
pip install --upgrade pip >/dev/null 2>&1 || true
pip install -r requirements.txt

touch ingest.log
chmod 664 ingest.log

# If already running, handle according to --force
if [ -f ingest.pid ]; then
  PID=$(cat ingest.pid || true)
  if [ -n "${PID}" ] && ps -p "$PID" >/dev/null 2>&1; then
    if [ "$FORCE" -eq 1 ]; then
      echo "Stopping existing ingestion (PID=$PID)..."
      kill "$PID" || true
      sleep 1
    else
      echo "Ingestion already running (PID=$PID). Use --force to restart."
      exit 0
    fi
  fi
fi

# Build args
ARGS=("--rps" "$RPS_VAL" "--backfill-minutes" "$BACKFILL_VAL")
if [ -n "$SUBREDDITS_VAL" ]; then
  ARGS+=("--subreddits" "$SUBREDDITS_VAL")
fi

# Start
nohup ./.venv/bin/python reddit_ingest.py "${ARGS[@]}" >> ingest.log 2>&1 &
echo $! > ingest.pid
sleep 2

if ps -p "$(cat ingest.pid 2>/dev/null || echo 0)" >/dev/null 2>&1; then
  echo "Reddit ingestion started. PID=$(cat ingest.pid). Tailing last 20 lines of ingest.log:"
  tail -n 20 ingest.log || true
else
  echo "Failed to start ingestion. See ingest.log for details." >&2
  tail -n 50 ingest.log || true
  exit 1
fi
