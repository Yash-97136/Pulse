# Pulse Ingestion Service (Reddit)

This directory contains the Reddit ingestion script that feeds the pipeline via Redis Streams or Kafka (Avro). In production, we use Redis Streams.

## Quick start (Redis Streams)

1. Copy `.env.example` to `.env` and fill in your Reddit API credentials and Redis connection:

   - `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET`, `REDDIT_USER_AGENT` (if the value contains spaces, quote it)
   - `REDIS_HOST`, `REDIS_PORT`, `REDIS_DB`
   - Set `SINK=redis`

2. Start ingestion:

   ```bash
   ./start-reddit.sh --rps 4 --backfill-minutes 60
   ```

   - Logs go to `ingest.log`
   - PID saved to `ingest.pid`

3. Stop ingestion:

   ```bash
   ./stop-reddit.sh
   ```

### Notes

- The script auto-creates a local Python virtualenv in `.venv` and installs `requirements.txt`.
- The Python ingestion reads `.env` directly via python-dotenv. The start script does not `source` it, so quoting is safe.
- You can also set `SUBREDDITS`, `RPS`, `BACKFILL_MINUTES` in your `.env`; flags here override those.
- If you see `tail: ingest.log: No such file or directory`, re-run the start script; it will create the log file.

## Troubleshooting

- Intermittent Reddit 503 or 429 errors are normal under heavy load. The script backs off automatically.
- To restart a stuck process:

  ```bash
  ./start-reddit.sh --force
  ```

- To verify the Redis stream is receiving data, on the Redis host run:

  ```
  XLEN raw_posts
  XINFO GROUPS raw_posts
  ZCARD trends:global
  ```
