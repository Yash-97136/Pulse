# Pulse — Free Oracle Cloud (OCI) deployment (Always Free)

This guide gets your stack running for $0 on OCI Always Free. It uses two Ampere A1 (ARM) VMs, Docker Compose for Kafka/Schema Registry/Redis/Postgres, and Caddy for HTTPS. No code changes required.

## Overview
- VM1 (data-plane): Kafka (Confluent), ZooKeeper, Schema Registry, Redis, Postgres
- VM2 (app-plane): processing-service, anomaly-service, api-service, reddit-ingestion, Caddy (TLS + reverse proxy), static frontend
- DNS/TLS: Cloudflare or your registrar for DNS, Let’s Encrypt with Caddy for TLS

Why two VMs? The Always Free tier gives enough CPU/RAM to split services for stability. You can also run everything on one bigger VM if you prefer (adjust ports accordingly).

## 0) Prereqs
- OCI account with Always Free enabled
- Two Ampere A1 VMs (Ubuntu 22.04 LTS recommended):
  - VM1 (data-plane): 2 OCPU, 12 GB RAM, 100 GB boot volume
  - VM2 (app-plane): 1–2 OCPU, 6–12 GB RAM, 50 GB boot volume
- Security Lists / NSGs open:
  - SSH: 22 (both VMs)
  - Web: 80, 443 (VM2 only)
  - Internal only: keep 9092/29092, 6379, 5432, 8081 restricted to the VPC (do not expose to internet)
- Domain (e.g., example.com) with DNS control (Cloudflare free works well)

## 1) Install Docker & Compose plugin on both VMs
```bash
sudo apt-get update -y
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update -y
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
newgrp docker
```

## 2) Clone repo on both VMs
```bash
cd ~
git clone https://github.com/Yash-97136/Pulse.git
```

## 3) VM1 (data-plane): Start infra services
Use the provided Compose file at `infra/oracle/docker-compose.yml`.

```bash
cd ~/Pulse/infra/oracle
# Optional: tune retention to save disk (defaults are already sensible)
docker compose up -d

# Create topics (init-topics runs automatically). Verify:
docker compose logs init-topics | tail -n +1
```

Ports (VM-internal):
- Kafka (broker: PLAINTEXT 29092 for containers; PLAINTEXT_HOST 9092 for host)
- Schema Registry: 8081
- Redis: 6379
- Postgres: 5432 (user/password: pulse/pulse; db: pulse)

## 4) Initialize DB schema (one-time)
```bash
# From VM1
cd ~/Pulse
cat <<'SQL' | docker compose -f infra/oracle/docker-compose.yml exec -T postgres psql -U pulse -d pulse -v ON_ERROR_STOP=1
CREATE TABLE IF NOT EXISTS anomalies (
  id BIGSERIAL PRIMARY KEY,
  keyword TEXT NOT NULL,
  current_count BIGINT NOT NULL,
  average_count DOUBLE PRECISION NOT NULL,
  stddev DOUBLE PRECISION NOT NULL,
  z_score DOUBLE PRECISION NOT NULL,
  detected_at TIMESTAMPTZ NOT NULL,
  window_start TIMESTAMPTZ NULL,
  window_end TIMESTAMPTZ NULL
);
SQL
```

## 5) VM2 (app-plane): Run backend services
In three terminals or using tmux/systemd, run:

```bash
# processing-service
cd ~/Pulse/backend/pulse-processing-service
KAFKA_BOOTSTRAP_SERVERS=<VM1-private-ip>:9092 \
SCHEMA_REGISTRY_URL=http://<VM1-private-ip>:8081 \
REDIS_HOST=<VM1-private-ip> \
./mvnw spring-boot:run
```

```bash
# anomaly-service
cd ~/Pulse/backend/pulse-anomaly-service
KAFKA_BOOTSTRAP_SERVERS=<VM1-private-ip>:9092 \
SCHEMA_REGISTRY_URL=http://<VM1-private-ip>:8081 \
DB_URL=jdbc:postgresql://<VM1-private-ip>:5432/pulse \
DB_USER=pulse \
DB_PASSWORD=pulse \
REDIS_HOST=<VM1-private-ip> \
SCHEDULE_INTERVAL_MS=10000 \
Z_THRESHOLD=3.0 HISTORY_WINDOW=360 ANOMALY_MIN_Z_STEP=1.0 \
./mvnw spring-boot:run
```

```bash
# api-service (port 8086 internally)
cd ~/Pulse/backend/pulse-api-service
DB_URL=jdbc:postgresql://<VM1-private-ip>:5432/pulse \
DB_USER=pulse \
DB_PASSWORD=pulse \
REDIS_HOST=<VM1-private-ip> \
./mvnw spring-boot:run
```

### Ingestion worker
```bash
cd ~/Pulse/backend/pulse-ingestion-service
python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
# Set Reddit creds as env vars or .env in this directory
./.venv/bin/python reddit_ingest.py \
  --subreddits 'technology+worldnews+news+AskReddit+politics+science+gaming+movies+Music+CryptoCurrency+business+programming+ai+dataisbeautiful' \
  --rps 10 --backfill-minutes 120
```

Note: if you previously used a command with `python` twice, drop the extra word; the correct form starts with `./.venv/bin/python reddit_ingest.py ...`.

## 6) VM2: Configure Caddy (TLS + reverse proxy)
`infra/oracle/Caddyfile` is included. Edit domains and copy it to `/etc/caddy/Caddyfile`:

```bash
sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt-get update -y && sudo apt-get install -y caddy

sudo mkdir -p /var/www/pulse
# Build frontend and copy
cd ~/Pulse/frontend/pulse-web
TRENDS_API_URL=https://api.yourdomain.com npm ci && TRENDS_API_URL=https://api.yourdomain.com npm run build
sudo rsync -av --delete dist/ /var/www/pulse/

# Put your edited Caddyfile in place
sudo cp ~/Pulse/infra/oracle/Caddyfile /etc/caddy/Caddyfile
sudo sed -i 's/app.example.com/yourapp.example.com/g; s/api.example.com/api.yourdomain.com/g' /etc/caddy/Caddyfile

# Start/enable
sudo systemctl enable --now caddy
```

The Caddyfile serves the static UI at `https://yourapp.example.com` and proxies `/api/*` to `http://localhost:8086`.

## 7) DNS
- app.yourdomain.com → VM2 public IP (A record)
- api.yourdomain.com → VM2 public IP (A record)
- Ensure ports 80/443 are open on VM2’s security list

## 8) Ops & backups
- Postgres backup (VM1): nightly cron to dump to OCI Object Storage
- Redis: relies on application TTLs; memory bounded by instance size
- Kafka: use modest retention (1–3 days) in compose (already set conservatively)
- Logs: keep local, set rotation via logrotate to save space

## 9) Cost & limits
- Within Always Free limits, this runs at $0. Watch egress bandwidth and disk usage.
- If you need more headroom later, you can vertical-scale VM sizes (paid) or move to managed services.

## Troubleshooting
- Ingestor errors: verify Reddit creds; ensure `SCHEMA_REGISTRY_URL` and `BOOTSTRAP_SERVERS` point to VM1
- Kafka topic counts: use kafka-ui at `http://VM1:8080` (exposed only inside VPC unless you open it)
- TLS: ensure DNS resolves before starting Caddy; it auto-issues certs

