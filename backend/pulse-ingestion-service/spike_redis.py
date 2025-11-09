#!/usr/bin/env python3
"""
Send a short, controlled spike of posts into the Redis stream used by Pulse.
This is useful to demo anomaly detection without waiting for a real event.

The records match the same JSON envelope that reddit_ingest.py produces:
XADD RAW_POSTS_STREAM payload=<json>

Usage:
  python spike_redis.py --keyword demo_spike_123 --count 100 --rps 10

Environment (optional; defaults shown):
  REDIS_HOST=localhost
  REDIS_PORT=6379
  REDIS_DB=0
  RAW_POSTS_STREAM=raw_posts
  RAW_POSTS_MAXLEN=100000
"""
import argparse
import json
import os
import time
import uuid
from typing import Dict

import redis

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_DB = int(os.getenv("REDIS_DB", "0"))
RAW_POSTS_STREAM = os.getenv("RAW_POSTS_STREAM", "raw_posts")
RAW_POSTS_MAXLEN = int(os.getenv("RAW_POSTS_MAXLEN", "100000"))


def make_rec(text: str) -> Dict[str, object]:
    now_ms = int(time.time() * 1000)
    return {
        "id": str(uuid.uuid4()),
        "text": text[:8000],
        "timestamp": now_ms,
        "source": "spike-test",
        "lang": "en",
    }


def main():
    ap = argparse.ArgumentParser(description="Emit a synthetic spike into Redis stream")
    ap.add_argument("--keyword", required=True, help="Unique keyword to spike (avoid common words)")
    ap.add_argument("--count", type=int, default=100, help="Number of messages to send")
    ap.add_argument("--rps", type=float, default=10.0, help="Messages per second")
    args = ap.parse_args()

    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=REDIS_DB, decode_responses=True)

    interval = 1.0 / args.rps if args.rps > 0 else 0.0
    print(f"Sending {args.count} msgs at ~{args.rps}/s into stream '{RAW_POSTS_STREAM}' with keyword '{args.keyword}'")

    sent = 0
    for i in range(args.count):
        text = f"Breaking: {args.keyword} surge event {i}! This is a demo spike for anomalies."
        rec = make_rec(text)
        try:
            r.xadd(
                RAW_POSTS_STREAM,
                {"payload": json.dumps(rec)},
                maxlen=RAW_POSTS_MAXLEN,
                approximate=True,
            )
            sent += 1
        except Exception as e:
            print(f"xadd failed: {e}")
        if interval > 0:
            time.sleep(interval)

    print(f"Done. Sent {sent} messages.")


if __name__ == "__main__":
    main()
