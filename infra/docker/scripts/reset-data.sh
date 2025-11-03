#!/usr/bin/env bash
set -euo pipefail

# Reset Kafka topics, Postgres anomalies table, and Redis keys for a clean local run.
# Usage: bash infra/docker/scripts/reset-data.sh

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(cd "$DIR/.." && pwd)"
cd "$COMPOSE_DIR"

# Detect docker compose command
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)
else
  echo "Error: Docker Compose not found. Install Docker Desktop or docker-compose." >&2
  exit 1
fi

echo "[1/4] Deleting Kafka topics (if exist)"
TOPICS=(raw_social_posts detected_anomalies)
for t in "${TOPICS[@]}"; do
  "${DC[@]}" exec -T broker kafka-topics --bootstrap-server broker:29092 --delete --topic "$t" || true
done

echo "[2/4] Recreating Kafka topics"
"${DC[@]}" run --rm init-topics

echo "[3/4] Truncating Postgres table: anomalies"
"${DC[@]}" exec -T postgres psql -U pulse -d pulse -c "TRUNCATE TABLE anomalies;"

echo "[4/4] Clearing Redis keys"
# Remove top-level trend ZSET and last-counts hash
"${DC[@]}" exec -T redis redis-cli -n 0 DEL trends:global trends:last_counts trends:lastSeen || true
# Remove per-key history lists
"${DC[@]}" exec -T redis sh -lc 'redis-cli -n 0 --scan --pattern "trends:history:*" | xargs -r redis-cli -n 0 DEL'
# Remove anomaly dedupe/last_z/cooldown keys
"${DC[@]}" exec -T redis sh -lc 'redis-cli -n 0 --scan --pattern "anomaly:*" | xargs -r redis-cli -n 0 DEL'

echo "\nStatus after reset:"
echo "- Redis DB size:"
"${DC[@]}" exec -T redis redis-cli -n 0 DBSIZE || true
echo "- Anomalies table count:"
"${DC[@]}" exec -T postgres psql -U pulse -d pulse -c "SELECT COUNT(*) FROM anomalies;" || true
echo "- Kafka topics:"
"${DC[@]}" exec -T broker kafka-topics --bootstrap-server broker:29092 --list || true

echo "\nReset complete."
