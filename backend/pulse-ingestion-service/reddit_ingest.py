#!/usr/bin/env python3
import argparse
import json
import os
import random
import re
import time
from pathlib import Path
from dotenv import load_dotenv  # NEW
from prawcore.exceptions import TooManyRequests

import praw
import redis  # durable de-dup across restarts
from confluent_kafka import SerializingProducer
from confluent_kafka.serialization import StringSerializer
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroSerializer

# Load local .env once (keeps secrets out of git)
load_dotenv(dotenv_path=(Path(__file__).resolve().parent / ".env"), override=False)

BOOTSTRAP_SERVERS = os.getenv("BOOTSTRAP_SERVERS", "localhost:9092")
SCHEMA_REGISTRY_URL = os.getenv("SCHEMA_REGISTRY_URL", "http://localhost:8081")
# Prefer RAW_POSTS_TOPIC if set; fall back to TOPIC
TOPIC = os.getenv("RAW_POSTS_TOPIC", os.getenv("TOPIC", "raw_social_posts"))
SINK = os.getenv("SINK", "redis").lower()  # "redis" (default) or "kafka" (Avro)
RAW_POSTS_STREAM = os.getenv("RAW_POSTS_STREAM", "raw_posts")
RAW_POSTS_MAXLEN = int(os.getenv("RAW_POSTS_MAXLEN", "100000"))

# (Removed kafka-json sink support; only Avro Kafka and Redis Streams remain)

# Reddit OAuth env vars (create an app at reddit.com/prefs/apps)
REDDIT_CLIENT_ID = os.getenv("REDDIT_CLIENT_ID")
REDDIT_CLIENT_SECRET = os.getenv("REDDIT_CLIENT_SECRET")
REDDIT_USER_AGENT = os.getenv("REDDIT_USER_AGENT", "pulse-ingestion/1.0 by localdev")

# Redis for cross-restart de-duplication
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_DB = int(os.getenv("REDIS_DB", "0"))
# TTL to remember seen Reddit IDs (seconds)
SEEN_TTL_SEC = int(os.getenv("REDDIT_SEEN_TTL_SECONDS", "86400"))  # default 24h

def load_schema() -> str:
    schema_path_env = os.getenv("SCHEMA_FILE")
    if schema_path_env:
        p = Path(schema_path_env)
    else:
        p = Path(__file__).resolve().parents[2] / "schemas" / "avro" / "raw_social_post.avsc"
    with open(p, "r", encoding="utf-8") as f:
        return f.read()

def identity_to_dict(obj, _ctx):
    return obj

def make_producer(schema_str: str) -> SerializingProducer:
    sr = SchemaRegistryClient({"url": SCHEMA_REGISTRY_URL})
    avro = AvroSerializer(schema_registry_client=sr, schema_str=schema_str, to_dict=identity_to_dict,
                          conf={"auto.register.schemas": True})
    return SerializingProducer({
        "bootstrap.servers": BOOTSTRAP_SERVERS,
        "key.serializer": StringSerializer("utf_8"),
        "value.serializer": avro,
        "linger.ms": 50,
        "batch.num.messages": 1000,
    })

# (Removed kafka-json producer)

def make_reddit() -> praw.Reddit:
    if not REDDIT_CLIENT_ID or not REDDIT_CLIENT_SECRET:
        raise RuntimeError("Set REDDIT_CLIENT_ID and REDDIT_CLIENT_SECRET")
    return praw.Reddit(
        client_id=REDDIT_CLIENT_ID,
        client_secret=REDDIT_CLIENT_SECRET,
        user_agent=REDDIT_USER_AGENT,
        # Let PRAW pause on rate-limit; we'll also handle 429 explicitly below
        ratelimit_seconds=5,
    )

def _sleep_for_rate_limit(headers: dict | None):
    # Reddit returns various hints; prefer Retry-After (seconds) or x-ratelimit-reset
    base = 60
    try:
        if headers:
            retry_after = headers.get("Retry-After") or headers.get("retry-after")
            if retry_after:
                base = max(base, int(float(retry_after)))
            reset = headers.get("x-ratelimit-reset") or headers.get("X-Ratelimit-Reset")
            if reset:
                base = max(base, int(float(reset)))
    except Exception:
        pass
    # Add small jitter to avoid thundering herd
    sleep_sec = base + random.uniform(1, 5)
    print(f"Rate limited (429). Sleeping for {sleep_sec:.1f}s before retry…")
    time.sleep(sleep_sec)

def make_redis():
    return redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=REDIS_DB, decode_responses=True)

def clean_markdown(text: str) -> str:
    if not text:
        return ""
    # Drop the old-Reddit boilerplate line
    text = re.sub(r"This post contains content not supported on old Reddit\.[^\n]*", "", text, flags=re.I)
    # Keep link text, drop URLs: [label](url) -> label
    text = re.sub(r"\[([^\]]+)\]\((?:https?://|/)[^)]+\)", r"\1", text)
    # Drop bare URLs
    text = re.sub(r"https?://\S+", "", text)
    # Collapse whitespace
    return re.sub(r"\s+", " ", text).strip()

def submission_to_record(s) -> dict:
    ts_ms = int(s.created_utc * 1000)
    title = (s.title or "").strip()

    # Use body only for self-posts; media/link posts often have boilerplate
    body = ""
    try:
        if getattr(s, "is_self", False):
            body = clean_markdown(getattr(s, "selftext", "") or "")
    except Exception:
        body = ""

    # Optionally enrich crossposts with original title
    try:
        if getattr(s, "crosspost_parent_list", None):
            orig = s.crosspost_parent_list[0]
            orig_title = (orig.get("title") or "").strip()
            if orig_title and orig_title.lower() not in title.lower():
                title = f"{title} {orig_title}".strip()
    except Exception:
        pass

    text = f"{title} {body}".strip()
    return {
        "id": s.id,
        "text": text[:8000],
        "timestamp": ts_ms,
        "source": "reddit",
        "lang": "en",
    }

def produce_submission_kafka(p: SerializingProducer, s):
    rec = submission_to_record(s)
    p.produce(
        topic=TOPIC,
        key=rec["id"],
        value=rec,
        headers=[("source", b"reddit"), ("subreddit", s.subreddit.display_name.encode("utf-8"))],
        timestamp=rec["timestamp"],
    )
    p.poll(0)

def produce_submission_redis(r: redis.Redis, s):
    rec = submission_to_record(s)
    try:
        r.xadd(
            RAW_POSTS_STREAM,
            {"payload": json.dumps(rec)},
            maxlen=RAW_POSTS_MAXLEN,
            approximate=True,
        )
    except Exception:
        # Best-effort: don't crash the loop on transient Redis issue
        pass

# (Removed kafka-json sink)

def main():
    ap = argparse.ArgumentParser(description="Pulse Reddit -> Kafka producer")
    ap.add_argument(
        "--subreddits",
        default=os.getenv(
            "SUBREDDITS",
            "technology+wallstreetbets+worldnews+politics+gaming+movies",
        ),
        help=(
            "Subreddit list, '+' joined (e.g. 'technology+wallstreetbets+worldnews+politics+gaming+movies'). "
            "Default: curated list; override with SUBREDDITS env or this flag."
        ),
    )
    ap.add_argument("--rps", type=float, default=float(os.getenv("RPS", "5")),
                    help="Max messages per second")
    ap.add_argument("--skip-existing", action="store_true", help="Skip current backlog and only stream new")
    ap.add_argument(
        "--backfill-minutes",
        type=int,
        default=int(os.getenv("BACKFILL_MINUTES", "0")),
        help=(
            "Before streaming, backfill recent posts from subreddit.new() within the last N minutes. "
            "Use 0 to disable. Example: --backfill-minutes 180"
        ),
    )
    args = ap.parse_args()

    schema = load_schema()
    prod = make_producer(schema) if SINK == "kafka" else None
    reddit = make_reddit()
    rdb = make_redis()

    sr_name = args.subreddits
    sr = reddit.subreddit(sr_name)
    sink_desc = TOPIC if SINK == "kafka" else f"redis-stream:{RAW_POSTS_STREAM}"
    print(f"Streaming submissions from r/{sr_name} → {sink_desc} | RPS≈{args.rps}")

    interval = 1.0 / args.rps if args.rps > 0 else 0.0
    # In-process de-dupe (belt) + Redis de-dupe (suspenders)
    seen = set()

    # Optional: backfill recent submissions to quickly warm up the pipeline
    sent = 0
    if args.backfill_minutes and args.backfill_minutes > 0:
        cutoff = time.time() - (args.backfill_minutes * 60)
        try:
            # PRAW caps default .new() pagination; limit=None iterates available pages within API constraints
            for s in sr.new(limit=None):
                if getattr(s, "created_utc", 0) < cutoff:
                    # new() yields newest-first; once we pass cutoff we can stop early
                    break
                if s.stickied or s.over_18 or s.removed_by_category:
                    continue
                # backfill uses the same Redis/in-process de-dupe as stream
                if s.id in seen:
                    continue
                seen_key = f"reddit:seen:{s.id}"
                try:
                    if not rdb.set(seen_key, "1", ex=SEEN_TTL_SEC, nx=True):
                        continue
                except Exception:
                    pass
                seen.add(s.id)
                if SINK == "kafka" and prod is not None:
                    produce_submission_kafka(prod, s)
                else:
                    produce_submission_redis(rdb, s)
                sent += 1
                if interval > 0:
                    time.sleep(interval)
        except Exception as e:
            print(f"Backfill skipped due to error: {e}")

    # PRAW stream handles rate limits; pause_after=0 for non-blocking generator
    stream = sr.stream.submissions(skip_existing=args.skip_existing, pause_after=0)

    # continue counting in stream loop
    try:
        while True:
            any_item = False
            try:
                for s in stream:
                    any_item = True
                    if s is None:
                        break
                    # basic filters
                    if s.stickied or s.over_18 or s.removed_by_category:
                        continue
                    # Per-process de-dupe
                    if s.id in seen:
                        continue
                    # Cross-restart de-dupe using Redis NX+TTL
                    seen_key = f"reddit:seen:{s.id}"
                    try:
                        # Only set if not exists; expire after SEEN_TTL_SEC
                        if not rdb.set(seen_key, "1", ex=SEEN_TTL_SEC, nx=True):
                            continue  # already produced recently
                    except Exception as e:
                        # If Redis is unavailable, fall back to in-process de-dupe only
                        pass
                    seen.add(s.id)
                    if SINK == "kafka" and prod is not None:
                        produce_submission_kafka(prod, s)
                    else:
                        produce_submission_redis(rdb, s)
                    sent += 1
                    if interval > 0:
                        time.sleep(interval)
            except TooManyRequests as e:
                # Respect rate limits, then recreate the stream and continue
                headers = getattr(e, "response", None)
                headers = getattr(headers, "headers", None)
                _sleep_for_rate_limit(headers)
                stream = sr.stream.submissions(skip_existing=True, pause_after=0)
            if not any_item:
                # backoff briefly when no new items
                time.sleep(0.5)
    except KeyboardInterrupt:
        pass
    finally:
        print(f"Flushing... sent={sent}")
        if SINK == "kafka" and prod is not None:
            prod.flush(10)
        print("Done.")

if __name__ == "__main__":
    main()