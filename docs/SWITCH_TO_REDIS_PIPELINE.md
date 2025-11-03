# Switch pipeline to Redis Streams (single-VM friendly)

This enables the ingestion → processing path to run without Kafka/Schema Registry by using Redis Streams.

## What changes were made
- Ingestion (`backend/pulse-ingestion-service/reddit_ingest.py`) can now publish to a Redis Stream when `SINK=redis`.
- Processing service adds a Redis Streams consumer active under profile `redis-pipeline` and disables Kafka listeners under that profile.

## Configuration
- Redis stream name: `RAW_POSTS_STREAM` (default: `raw_posts`)
- Processing group: `REDIS_STREAM_GROUP` (default: `pulse-processing`)
- Processing consumer: `REDIS_STREAM_CONSUMER` (default: `processor-1`)

These map to the following Spring properties (no changes needed unless you want to customize):
- `pulse.redis.stream.name`
- `pulse.redis.stream.group`
- `pulse.redis.stream.consumer`

## How to run (locally or on a single VM)
1) Start Redis and Postgres (Kafka not required)
   - You can reuse `infra/docker/docker-compose.yml` but you only need Redis and Postgres services, or start your own Redis/Postgres.

2) Processing Service (Redis Streams mode)
   - Set profile: `spring.profiles.active=redis-pipeline`
   - Ensure Redis host/port env: `REDIS_HOST`, `REDIS_PORT`
   - Example JVM flags: `-Dspring.profiles.active=redis-pipeline`

3) Ingestion (publish to Redis Streams)
   - Set `SINK=redis`
   - Optional: `RAW_POSTS_STREAM=raw_posts`
   - Other env: `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET`, `REDDIT_USER_AGENT`, `REDIS_HOST`, `REDIS_PORT`
   - You can use `--backfill-minutes 60` to seed recent posts and `--rps` to control pacing.

4) API & Anomaly services
   - No change required for trends endpoints and anomaly detection storage. SSE for anomalies still uses Kafka; if Kafka is not running, the SSE stream won't emit events, but REST queries for anomalies continue to work.

## Notes
- The ingestion script still supports Kafka; switching is runtime via `SINK` env.
- The Redis consumer creates the consumer group if missing.
- Messages are acknowledged automatically after processing.
