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
import random
import string
import time
import uuid
from typing import Dict, List

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


def gen_noise_tokens(n: int) -> List[str]:
    out = []
    for _ in range(n):
        # short random lowercase token (avoids stopwords list by being random)
        token = ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
        out.append(token)
    return out


def warmup_text(keyword: str, include_keyword: bool, idx: int) -> str:
    noise = gen_noise_tokens(3)
    parts = []
    if include_keyword:
        parts.append(keyword)
    parts.extend(noise)
    # Avoid fixed English words that might trend (e.g., 'baseline', 'build')
    return " ".join(parts + [str(idx)])


def spike_text(keyword: str, idx: int) -> str:
    # Keep payload concise & single-keyword dominant; avoid extra trending words
    return f"{keyword} {idx}"


def send_posts(r: redis.Redis, make_text_fn, total: int, interval: float):
    sent = 0
    for i in range(total):
        text = make_text_fn(i)
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
    return sent


def main():
    ap = argparse.ArgumentParser(description="Emit a synthetic warm-up + spike into Redis stream")
    ap.add_argument("--keyword", required=True, help="Unique keyword to spike (avoid common words)")
    ap.add_argument("--warmup-count", dest="warmup_count", type=int, default=0, help="Number of warm-up (mixed) posts before spike")
    ap.add_argument("--warmup-keyword-ratio", dest="warmup_keyword_ratio", type=float, default=0.3, help="Fraction of warm-up posts containing the keyword (0-1)")
    ap.add_argument("--count", type=int, default=100, help="Number of spike posts (all with keyword)")
    ap.add_argument("--rps", type=float, default=10.0, help="Messages per second for warm-up (and spike if --spike-rps not set)")
    ap.add_argument("--spike-rps", dest="spike_rps", type=float, default=None, help="Messages per second for spike phase only (optional)")
    args = ap.parse_args()

    if not (0.0 <= args.warmup_keyword_ratio <= 1.0):
        raise SystemExit("--warmup-keyword-ratio must be between 0 and 1")

    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=REDIS_DB, decode_responses=True)
    interval = 1.0 / args.rps if args.rps and args.rps > 0 else 0.0

    # Warm-up phase
    if args.warmup_count > 0:
        print(f"Warm-up: sending {args.warmup_count} mixed posts (~{args.rps}/s), keyword ratio {args.warmup_keyword_ratio}")
        def make_warm(i: int):
            include_kw = random.random() < args.warmup_keyword_ratio
            return warmup_text(args.keyword, include_kw, i)
        warm_sent = send_posts(r, make_warm, args.warmup_count, interval)
        print(f"Warm-up complete. Sent {warm_sent} posts.")
        # Short pause to let scheduler tick & history accumulate
        time.sleep(min(5.0, max(1.0, args.warmup_count / (args.rps * 10))))

    # Spike phase
    spike_rps = args.spike_rps if args.spike_rps is not None else args.rps
    spike_interval = 1.0 / spike_rps if spike_rps and spike_rps > 0 else 0.0
    print(f"Spike: sending {args.count} posts (~{spike_rps}/s) with keyword '{args.keyword}'")
    def make_spike(i: int):
        return spike_text(args.keyword, i)
    spike_sent = send_posts(r, make_spike, args.count, spike_interval)
    print(f"Spike complete. Sent {spike_sent} posts.")
    print("Done.")


if __name__ == "__main__":
    main()
