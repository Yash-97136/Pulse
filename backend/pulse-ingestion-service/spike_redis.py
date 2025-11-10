#!/usr/bin/env python3
"""
Send a short, controlled spike of posts into the Redis stream used by Pulse.
This is useful to demo anomaly detection without waiting for a real event.

The records match the same JSON envelope that reddit_ingest.py produces:
XADD RAW_POSTS_STREAM payload=<json>

Usage:
    Basic spike:
        python spike_redis.py --keyword demo_spike_123 --count 100 --rps 10

    With warm-up phase (build baseline & history before main spike):
        python spike_redis.py --keyword demo_spike_123 --warmup-count 200 --warmup-keyword-ratio 0.3 --count 300 --rps 8 --spike-rps 20

Warm-up rationale:
    The anomaly detector needs a baseline (mean/stddev) and history samples. A warm-up phase
    creates mixed posts where the keyword appears in only a fraction of documents so its
    document-frequency ratio stays below suppression threshold (df-max-ratio) longer, letting
    the global ZSET score accumulate gradually. After baseline forms, the main spike phase
    sends every post with the keyword to produce a sharp jump in count and Z-score.

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
from typing import Dict, List

import redis

SCHEDULER_INTERVAL_SEC = 5.1   # anomaly scheduler period + small buffer
MIN_SAMPLES = 5                # build 5 baseline points
POSTS_PER_PULSE = 100          # per baseline pulse
KEYWORD_RATIO = 0.5            # 50% of posts contain the keyword (stable baseline)
SPIKE_POSTS = 500              # final spike: all contain the keyword

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_DB   = int(os.getenv("REDIS_DB", "0"))
STREAM     = os.getenv("RAW_POSTS_STREAM", "raw_posts")
MAXLEN     = int(os.getenv("RAW_POSTS_MAXLEN", "100000"))


def make_post(text: str):
    return {
        "id": str(uuid.uuid4()),
        "text": text[:8000],
        "timestamp": int(time.time() * 1000),
        "source": "demo-spike",
        "lang": "en",
    }


def send_pulse(r: redis.Redis, keyword: str, posts: int, ratio: float):
    kw_limit = int(posts * ratio + 0.5)
    pipe = r.pipeline(transaction=False)
    sent_kw = 0
    for i in range(posts):
        text = f"{keyword} pulse {i}" if i < kw_limit else f"noise {i}"
        pipe.xadd(STREAM, {"payload": json.dumps(make_post(text))}, maxlen=MAXLEN, approximate=True)
        if i < kw_limit: sent_kw += 1
    pipe.execute()
    print(f"  pulse: {posts} posts, {sent_kw} with '{keyword}'")


def clear_state(r: redis.Redis, kw: str):
    print(f"Clearing Redis state for '{kw}'")
    r.delete(f"trends:history:{kw}")
    r.zrem("trends:global", kw)
    r.hdel("trends:last_counts", kw)
    r.delete(f"anomaly:last_emitted_z:{kw}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keyword", required=True)
    ap.add_argument("--pulses", type=int, default=MIN_SAMPLES)
    ap.add_argument("--posts-per-pulse", type=int, default=POSTS_PER_PULSE)
    ap.add_argument("--ratio", type=float, default=KEYWORD_RATIO)
    ap.add_argument("--spike-posts", type=int, default=SPIKE_POSTS)
    args = ap.parse_args()

    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=REDIS_DB, decode_responses=True)
    clear_state(r, args.keyword); time.sleep(1)

    print(f"Building baseline ({args.pulses} samples)...")
    for i in range(args.pulses):
        print(f" baseline {i+1}/{args.pulses}")
        send_pulse(r, args.keyword, args.posts_per_pulse, args.ratio)
        print(f"  waiting {SCHEDULER_INTERVAL_SEC}s for scheduler...")
        time.sleep(SCHEDULER_INTERVAL_SEC)

    print(f"Sending spike of {args.spike_posts} posts...")
    send_pulse(r, args.keyword, args.spike_posts, 1.0)
    print("Done. Check anomalies in ~10s.")


if __name__ == "__main__":
    main()
