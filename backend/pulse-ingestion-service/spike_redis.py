#!/usr/bin/env python3
"""
Deterministic spike script optimized for anomaly detection.

Sends controlled pulses separated by scheduler intervals to build a low-variance
baseline, then sends a final spike to trigger a high z-score anomaly.
"""
import argparse
import json
import os
import time
import uuid
from typing import Dict
import redis

# Scheduler settings (match anomaly service config)
SCHEDULER_INTERVAL_SEC = 5.2  # 5000ms + 200ms buffer for safety
MIN_SAMPLES = 5               # min-samples in anomaly config (includes current)

# Redis connection
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_DB = int(os.getenv("REDIS_DB", "0"))
RAW_POSTS_STREAM = os.getenv("RAW_POSTS_STREAM", "raw_posts")
RAW_POSTS_MAXLEN = int(os.getenv("RAW_POSTS_MAXLEN", "100000"))

def make_post(text: str) -> Dict[str, object]:
    """Create a single post record."""
    return {
        "id": str(uuid.uuid4()),
        "text": text[:8000],
        "timestamp": int(time.time() * 1000),
        "source": "demo-spike",
        "lang": "en",
    }

def send_pulse(r: redis.Redis, keyword: str, posts: int, rps: int = 0):
    """Send a burst of posts containing only the keyword."""
    pipe = r.pipeline(transaction=False)
    delay = 1.0 / rps if rps > 0 else 0
    
    for i in range(posts):
        # Every post contains only the keyword (clean, no extra words)
        rec = make_post(keyword)
        pipe.xadd(RAW_POSTS_STREAM, {"payload": json.dumps(rec)}, maxlen=RAW_POSTS_MAXLEN, approximate=True)
        if delay > 0 and i < posts - 1:
            time.sleep(delay)
    
    pipe.execute()
    print(f"  sent {posts} posts with '{keyword}'")

def clear_state(r: redis.Redis, kw: str):
    """Reset all Redis keys for a keyword."""
    print(f"Clearing state for '{kw}'")
    r.delete(f"trends:history:{kw}")
    r.zrem("trends:global", kw)
    r.hdel("trends:last_counts", kw)
    r.delete(f"anomaly:last_emitted_z:{kw}")
    r.delete(f"trends:df:{kw}")
    print("  done")

def compute_expected_z(r: redis.Redis, kw: str, zt: float = 3.0):
    """Compute expected z-score and needed posts (baseline excludes current, sample std)."""
    history_strs = r.lrange(f"trends:history:{kw}", 0, -1)
    if not history_strs or len(history_strs) < 2:
        print("  not enough history")
        return
    
    history = [int(x) for x in history_strs]
    current = history[0]
    baseline = history[1:]
    
    if len(baseline) < 2:
        print("  baseline too short for sample std")
        return
    
    mean = sum(baseline) / len(baseline)
    var = sum((x - mean) ** 2 for x in baseline) / (len(baseline) - 1)
    sd = var ** 0.5
    
    if sd == 0:
        print("  baseline sd=0")
        return
    
    z = (current - mean) / sd
    target = mean + zt * sd
    need = max(0, int(target - current + 0.5))
    
    print(f"  baseline_n={len(baseline)} mean={mean:.2f} sd={sd:.2f} current={current} z={z:.2f} need≈{need}")
    return need

def main():
    ap = argparse.ArgumentParser(description="Deterministic anomaly spike script")
    ap.add_argument("--keyword", required=True, help="Unique keyword to spike")
    ap.add_argument("--pulses", type=int, default=MIN_SAMPLES, help="Number of baseline pulses")
    ap.add_argument("--posts-per-pulse", type=int, default=10, help="Posts per baseline pulse (keep low for low variance)")
    ap.add_argument("--spike-posts", type=int, default=500, help="Final spike post count")
    ap.add_argument("--rps", type=int, default=0, help="Rate limit posts/sec (0=unlimited)")
    ap.add_argument("--show-z", action="store_true", help="Show z-score after spike")
    args = ap.parse_args()

    r = redis.Redis(
        host=REDIS_HOST, port=REDIS_PORT, db=REDIS_DB,
        decode_responses=True,
        socket_connect_timeout=5, socket_timeout=10
    )
    
    # Clear old state
    clear_state(r, args.keyword)
    time.sleep(1)

    # Build baseline with low variance (small posts-per-pulse = small increments)
    print(f"Building baseline ({args.pulses} pulses, {SCHEDULER_INTERVAL_SEC}s apart)...")
    for i in range(args.pulses):
        print(f" pulse {i+1}/{args.pulses}")
        send_pulse(r, args.keyword, args.posts_per_pulse, args.rps)
        if i < args.pulses - 1:
            print(f"  waiting {SCHEDULER_INTERVAL_SEC}s for scheduler...")
            time.sleep(SCHEDULER_INTERVAL_SEC)

    print(f"\nSending spike ({args.spike_posts} posts)...")
    send_pulse(r, args.keyword, args.spike_posts, args.rps)
    
    if args.show_z:
        print("\nWaiting for scheduler tick...")
        time.sleep(SCHEDULER_INTERVAL_SEC + 2)
        compute_expected_z(r, args.keyword)
        
        last_z = r.get(f"anomaly:last_emitted_z:{args.keyword}")
        if last_z:
            print(f"  anomaly emitted: z={last_z}")
        else:
            print("  no anomaly emitted yet")
    
    print("\nDone. Check dashboard or DB for anomaly.")

if __name__ == "__main__":
    main()
