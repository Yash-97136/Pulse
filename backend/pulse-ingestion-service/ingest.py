#!/usr/bin/env python3
import argparse
import json
import os
import random
import time
import uuid
from pathlib import Path

from confluent_kafka import SerializingProducer
from confluent_kafka.serialization import StringSerializer, SerializationContext, MessageField
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroSerializer

# Defaults for local docker-compose
BOOTSTRAP_SERVERS = os.getenv("BOOTSTRAP_SERVERS", "localhost:9092")
SCHEMA_REGISTRY_URL = os.getenv("SCHEMA_REGISTRY_URL", "http://localhost:8081")
TOPIC = os.getenv("TOPIC", "raw_social_posts")

# Load Avro schema from repo
def load_schema() -> str:
    # Allow overriding via env var
    schema_path_env = os.getenv("SCHEMA_FILE")
    if schema_path_env:
        schema_path = Path(schema_path_env)
    else:
        # Go up to repo root: Pulse/
        repo_root = Path(__file__).resolve().parents[2]
        schema_path = repo_root / "schemas" / "avro" / "raw_social_post.avsc"

    if not schema_path.exists():
        raise FileNotFoundError(f"Avro schema not found at {schema_path}")
    with open(schema_path, "r", encoding="utf-8") as f:
        return f.read()

def identity_to_dict(obj, ctx):
    # AvroSerializer expects a dict conversion function; our obj is already a dict
    return obj

def make_producer(schema_str: str) -> SerializingProducer:
    schema_registry_conf = {"url": SCHEMA_REGISTRY_URL}
    schema_registry_client = SchemaRegistryClient(schema_registry_conf)

    avro_serializer = AvroSerializer(
        schema_registry_client=schema_registry_client,
        schema_str=schema_str,
        to_dict=identity_to_dict,
        conf={
            # auto-register on first send; subject will be "<topic>-value"
            "auto.register.schemas": True
        },
    )

    producer_conf = {
        "bootstrap.servers": BOOTSTRAP_SERVERS,
        "key.serializer": StringSerializer("utf_8"),
        "value.serializer": avro_serializer,
        "linger.ms": 50,
        "batch.num.messages": 1000,
    }
    return SerializingProducer(producer_conf)

SAMPLES = [
    "New iPhone release looks amazing!",
    "Is the stock market crashing today?",
    "Best pizza places in NYC?",
    "Breaking news: major outage reported",
    "How to learn Spring Boot quickly",
    "Python vs Java for data engineering",
    "Does anyone use Redis streams?",
    "Kafka exactly-once semantics explained",
    "Chart.js tips for real-time dashboards",
    "AWS MSK pricing discussion thread",
]

def generate_post():
    now_ms = int(time.time() * 1000)
    return {
        "id": str(uuid.uuid4()),
        "text": random.choice(SAMPLES),
        "timestamp": now_ms,
        "source": "synthetic",
        "lang": "en",
    }

def delivery_report(err, msg):
    if err is not None:
        print(f"Delivery failed: {err}")
    else:
        print(f"Produced to {msg.topic()} [{msg.partition()}] @ offset {msg.offset()}")

def main():
    parser = argparse.ArgumentParser(description="Pulse synthetic Avro producer")
    parser.add_argument("--count", type=int, default=50, help="number of messages to send (ignored if --duration is set)")
    parser.add_argument("--rps", type=float, default=5.0, help="messages per second")
    parser.add_argument("--duration", type=float, default=0.0, help="duration in seconds to run continuously; if > 0, --count is ignored")
    args = parser.parse_args()

    schema_str = load_schema()
    producer = make_producer(schema_str)

    interval = 1.0 / args.rps if args.rps > 0 else 0.0
    if args.duration and args.duration > 0:
      print(f"Sending for ~{args.duration}s to topic '{TOPIC}' at ~{args.rps} msg/s (continuous mode)")
    else:
      print(f"Sending {args.count} messages to topic '{TOPIC}' at ~{args.rps} msg/s")
    print(f"Bootstrap: {BOOTSTRAP_SERVERS} | Schema Registry: {SCHEMA_REGISTRY_URL}")

    if args.duration and args.duration > 0:
        start = time.time()
        sent = 0
        try:
            while time.time() - start < args.duration:
                record = generate_post()
                key = record["id"]
                producer.produce(
                    topic=TOPIC,
                    key=key,
                    value=record,
                    on_delivery=delivery_report,
                    headers=[("source", b"synthetic")],
                    timestamp=record["timestamp"],
                )
                sent += 1
                producer.poll(0)
                if interval > 0:
                    time.sleep(interval)
        finally:
            print(f"Flushing... sent={sent}")
            producer.flush(10)
            print("Done.")
    else:
        for i in range(args.count):
            record = generate_post()
            key = record["id"]

            producer.produce(
                topic=TOPIC,
                key=key,
                value=record,
                on_delivery=delivery_report,
                headers=[("source", b"synthetic")],
                timestamp=record["timestamp"],
            )

            # Serve delivery callbacks
            producer.poll(0)
            if interval > 0:
                time.sleep(interval)

        print("Flushing...")
        producer.flush(10)
        print("Done.")

if __name__ == "__main__":
    main()