# Pulse — Free Oracle Cloud (OCI) deployment (Always Free)

This guide gets your stack running for $0 on OCI Always Free. It uses two Ampere A1 (ARM) VMs, Docker Compose for Redis/Postgres (or optional Kafka), and Caddy for HTTPS. No code changes required.

## Overview
- VM1 (data-plane): Redis, Postgres (stateful only)
- VM2 (app-plane): processing-service, anomaly-service, api-service, reddit-ingestion, Caddy (TLS + reverse proxy), static frontend
- DNS/TLS: Cloudflare or your registrar for DNS, Let’s Encrypt with Caddy for TLS

Why two VMs? The Always Free tier gives enough CPU/RAM to split services for stability. You can also run everything on one bigger VM if you prefer (adjust ports accordingly).

Pipeline modes supported:
- Redis Streams only (recommended for Always Free; no Kafka required)
- Kafka + Schema Registry (original pipeline; heavier footprint)

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

## 3) VM1 (data-plane): Start infra services (Redis + Postgres only)
Use the provided Compose file at `infra/oracle/docker-compose.data-plane.yml`.

```bash
cd ~/Pulse/infra/oracle
docker compose -f docker-compose.data-plane.yml up -d
docker compose -f docker-compose.data-plane.yml ps
```

Ports (VM-internal):
- Redis: 6379
- Postgres: 5432 (user/password: pulse/pulse; db: pulse)

## 4) Initialize DB schema (one-time)
```bash
# From VM1
cd ~/Pulse
cat <<'SQL' | docker compose -f infra/oracle/docker-compose.data-plane.yml exec -T postgres psql -U pulse -d pulse -v ON_ERROR_STOP=1
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

## 5) VM2 (app-plane): Run backend services (Redis Streams)
Set envs to point to the VM1 private IP. In three terminals or using tmux/systemd, run:

```bash
# processing-service (Redis Streams mode)
cd ~/Pulse/backend/pulse-processing-service
REDIS_HOST=<VM1-private-ip> \
REDIS_PORT=6379 \
JAVA_TOOL_OPTIONS="-Dspring.profiles.active=redis-pipeline" \
./mvnw spring-boot:run
```

```bash
# anomaly-service
cd ~/Pulse/backend/pulse-anomaly-service
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

### Ingestion worker (publish to Redis Streams)
```bash
cd ~/Pulse/backend/pulse-ingestion-service
python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
# Set Reddit creds as env vars or .env in this directory
SINK=redis REDIS_HOST=<VM1-private-ip> REDIS_PORT=6379 \
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

### 6b) Alternative: Nginx + Certbot (DuckDNS or any domain)
If you prefer Nginx or are using a dynamic DNS provider like DuckDNS, use this path. It serves the SPA and proxies the API, then issues a Let’s Encrypt cert via Certbot.

Prereqs:
- Your domain (e.g., `pulseapp.duckdns.org`) must resolve to the VM2 public IP (verify with `dig +short <host>`)
- VM2: ports 80 and 443 allowed in OCI Security List/NSG (host firewall `ufw` should allow or be inactive)

1) Install Nginx (if not already):
```bash
sudo apt-get update -y || true  # If this fails due to a third-party repo, continue and install nginx only
sudo apt-get install -y nginx
```

2) Serve the frontend and proxy the API:
```bash
sudo mkdir -p /var/www/pulse

# Build frontend and deploy static files
cd ~/Pulse/frontend/pulse-web
npm ci
npm run build
sudo rsync -av --delete dist/ /var/www/pulse/

# Nginx site for SPA + API proxy
cat | sudo tee /etc/nginx/sites-available/pulse > /dev/null <<'NGINX'
server {
  listen 80;
  server_name _;  # replaced to your domain below

  root /var/www/pulse;
  index index.html;

  location /api/ {
    proxy_pass http://127.0.0.1:8086/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }

  location / {
    try_files $uri $uri/ /index.html;
  }
}
NGINX

sudo ln -sf /etc/nginx/sites-available/pulse /etc/nginx/sites-enabled/pulse
sudo nginx -t && sudo systemctl reload nginx
```

3) Set your domain on the site:
```bash
DOMAIN=pulseapp.duckdns.org  # change to your host
sudo sed -i "s/server_name _;/server_name ${DOMAIN};/" /etc/nginx/sites-available/pulse
sudo nginx -t && sudo systemctl reload nginx
```

4) Install Certbot (snap recommended if apt is blocked by a bad repo):
```bash
# If apt-get update fails due to e.g. Caddy repo GPG key, use snap for Certbot
sudo snap install core && sudo snap refresh core
sudo snap install --classic certbot
sudo ln -sf /snap/bin/certbot /usr/bin/certbot
certbot --version
```

5) Issue certificate and enable redirect:
```bash
DOMAIN=pulseapp.duckdns.org  # change to your host
# Non-interactive issuance without email; replace with --email you@domain.com for proper registration
sudo certbot --nginx -d "$DOMAIN" \
  --agree-tos --non-interactive --register-unsafely-without-email --redirect
```

6) Verify HTTPS and auto-renew:
```bash
curl -I https://$DOMAIN
sudo systemctl status certbot.timer || sudo systemctl list-timers | grep certbot
```

Troubleshooting:
- If `curl -I https://...` times out but HTTP works, open port 443 in OCI Security List/NSG for the VM’s subnet.
- If Certbot shows DNS SERVFAIL, wait 1–2 minutes and retry; some dynamic DNS providers (e.g., DuckDNS) can transiently fail AAAA queries.
- If `apt-get update` fails with a Caddy repo GPG error, either add the missing key or temporarily remove that source; the snap-based Certbot path above avoids apt entirely.

## 7) DNS
- app.yourdomain.com → VM2 public IP (A record)
- api.yourdomain.com → VM2 public IP (A record)
- Ensure ports 80/443 are open on VM2’s security list

## 8) Ops & backups
- Postgres backup (VM1): nightly cron to dump to OCI Object Storage
- Redis: relies on application TTLs; memory bounded by instance size
- Kafka: only required if you need SSE anomaly stream. For Redis-only mode, REST endpoints work without Kafka.
- Logs: keep local, set rotation via logrotate to save space

## 9) Cost & limits
- Within Always Free limits, this runs at $0. Watch egress bandwidth and disk usage.
- If you need more headroom later, you can vertical-scale VM sizes (paid) or move to managed services.

## Troubleshooting
- Ingestor errors: verify Reddit creds; for Redis Streams mode ensure `SINK=redis` and `REDIS_HOST` point to VM1
- If running Kafka mode, kafka-ui is available at `http://VM1:8080` (VPC-only unless opened)
- TLS: ensure DNS resolves before starting Caddy; it auto-issues certs

