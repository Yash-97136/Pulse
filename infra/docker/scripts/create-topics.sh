#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP="broker:29092"

echo "Waiting for Kafka at ${BOOTSTRAP}..."
for i in {1..30}; do
  if kafka-topics --bootstrap-server "${BOOTSTRAP}" --list >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "Creating topics (idempotent)..."
kafka-topics --bootstrap-server "${BOOTSTRAP}" --create --if-not-exists --topic raw_social_posts --partitions 3 --replication-factor 1
kafka-topics --bootstrap-server "${BOOTSTRAP}" --create --if-not-exists --topic detected_anomalies --partitions 3 --replication-factor 1

echo "Done."
