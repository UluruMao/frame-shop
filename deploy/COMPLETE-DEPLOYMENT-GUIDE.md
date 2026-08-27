# Frame Shop — Linux Server Deployment Guide (Complete)

> Step-by-step: VM creation → Ubuntu install → harden (firewall) → deploy the app → backup strategy.
> ISO: `ubuntu-26.04-live-server-amd64.iso`

---

## Part 1: Create the VM & install Ubuntu Server

### 1.1 Create the VM (VirtualBox example)

1. Download VirtualBox (https://www.virtualbox.org/) if you don't have it.
2. Open VirtualBox → click **New**.
3. **Name:** `frame-shop-server`
4. **ISO Image:** select `ubuntu-26.04-live-server-amd64.iso`
5. **Memory:** 4096 MB (4 GB) minimum — 8192 MB recommended
6. **Disk:** 25 GB minimum (dynamically allocated is fine)
7. **Network:** leave as NAT (you'll configure port forwarding later), or switch to **Bridged Adapter** (Settings → Network) so the VM gets its own IP on your LAN — this is easier for SSH + browser testing.

### 1.2 Install Ubuntu Server

1. Start the VM. It boots from the ISO.
2. Choose your language.
3. **Installer update available?** → Continue without updating (or update, your choice).
4. **Keyboard layout** → pick yours.
5. **Type of installation** → Ubuntu Server (default, not "minimized").
6. **Network:** it should auto-detect DHCP and show an IP (e.g. `192.168.1.105`). **Write this IP down** — you'll SSH to it.
7. **Proxy:** leave blank.
8. **Mirror:** use the default.
9. **Storage:** use entire disk (the 25 GB VDI). Confirm "Set up this disk as an LVM group" (gives you flexibility for backups later). Don't encrypt the disk for a dev VM.
10. **Profile setup:**
    - Your name: e.g. `tcmao`
    - Server name (hostname): `frame-server`
    - Username: `tcmao`
    - Password: choose one, **write it down**
11. **SSH Setup:** ✅ check **"Install OpenSSH server"** — critical, this is how you'll connect.
12. **Featured snaps:** skip (don't select any — you'll install packages via apt).
13. Wait for the install to finish → "Reboot Now" → press Enter.

### 1.3 First login (from the VM console or SSH)

After reboot, the VM console shows the login prompt. You can either:
- **Type directly in the VM console** (click into the VirtualBox window), or
- **SSH from your Windows Git Bash**: `ssh tcmao@192.168.1.105` (replace with your VM's IP)

**If SSH doesn't connect** (NAT network), either:
- Switch the VM network to "Bridged Adapter" (VM Settings → Network → Attached to: Bridged Adapter), reboot, check the new IP, or
- Set up port forwarding in VirtualBox: Settings → Network → Advanced → Port Forwarding → add rule: Host Port 2222 → Guest Port 22. Then SSH: `ssh -p 2222 tcmao@localhost`

From here on, all commands run **on the Ubuntu server** (via SSH or the VM console), unless noted as "on Windows."

---

## Part 2: Firewall hardening (against hackers)

**Do this BEFORE deploying the app** — lock down the server first.

### 2.1 Update the system

```bash
sudo apt update && sudo apt upgrade -y
```

### 2.2 Enable UFW (Uncomplicated Firewall)

```bash
# Allow SSH (so you don't lock yourself out!)
sudo ufw allow 22/tcp

# Allow HTTP (Nginx will serve the app on port 80)
sudo ufw allow 80/tcp

# Allow HTTPS (if you add SSL/TLS later)
sudo ufw allow 443/tcp

# Enable the firewall
sudo ufw enable

# Verify
sudo ufw status verbose
```

**What this does:** blocks ALL incoming connections except SSH (22), HTTP (80), and HTTPS (443). MariaDB (3306) and Redis (6379) are NOT exposed to the network — they only listen on `localhost`, and the firewall blocks any external access to them even if they did.

### 2.3 Secure SSH (prevent brute-force attacks)

```bash
# Install fail2ban — auto-bans IPs that fail SSH login too many times
sudo apt install -y fail2ban

# It starts automatically. Verify:
sudo systemctl status fail2ban

# The default config bans an IP after 5 failed attempts for 10 minutes.
# To make it stricter (optional):
sudo cp /etc/fail2ban/jail.conf /etc/fail2ban/jail.local
sudo nano /etc/fail2ban/jail.local
# Under [sshd], set: maxretry = 3, bantime = 3600
# Save: Ctrl+O, Enter, Ctrl+X
sudo systemctl restart fail2ban
```

### 2.4 Disable SSH password login (optional, more secure — use SSH keys)

```bash
# On your WINDOWS machine (Git Bash), generate an SSH key pair:
ssh-keygen -t ed25519        # press Enter through all prompts

# Copy the public key to the server:
ssh-copy-id tcmao@192.168.1.105    # enter your password one last time

# Test: you should now SSH without a password:
ssh tcmao@192.168.1.105

# Once key login works, disable password login ON THE SERVER:
sudo nano /etc/ssh/sshd_config
# Find and change: PasswordAuthentication no
# Save: Ctrl+O, Enter, Ctrl+X
sudo systemctl restart sshd
```

**⚠ Keep your SSH key safe.** If you lose it, you'll need console access (the VirtualBox VM window) to re-enable password auth.

### 2.5 Verify MariaDB & Redis are NOT network-exposed

After you install them (Part 3), they should only listen on `127.0.0.1`:

```bash
# After installing MariaDB + Redis (Part 3), verify:
sudo ss -tlnp | grep -E '3306|6379'
# Should show 127.0.0.1:3306 and 127.0.0.1:6379 (NOT 0.0.0.0)
```

If either shows `0.0.0.0`, edit the config:
- MariaDB: `sudo nano /etc/mysql/mariadb.conf.d/50-server.cnf` → `bind-address = 127.0.0.1`
- Redis: `sudo nano /etc/redis/redis.conf` → `bind 127.0.0.1`

---

## Part 3: Install the runtime (JDK, MariaDB, Redis, Nginx)

### 3.1 Install all packages

```bash
sudo apt install -y openjdk-21-jdk-headless mariadb-server redis-server nginx
```

Verify:
```bash
java -version          # openjdk 21.x
mariadb --version      # 10.x or 11.x
redis-cli ping         # PONG
nginx -v               # nginx version
```

### 3.2 Secure MariaDB

```bash
sudo mysql_secure_installation
```
- Enter current root password: **press Enter** (none set yet)
- Set root password? **Y** → choose a strong password, **write it down**
- Remove anonymous users? **Y**
- Disallow remote root login? **Y**
- Remove test database? **Y**
- Reload privilege tables? **Y**

### 3.3 Create the database + tables + app user

```bash
# Copy schema-prod.sql to the server first (from Windows — see Part 4)
# Then on the server:
sudo mariadb -u root -p < /opt/frame/deploy/schema-prod.sql
# Enter your MariaDB root password (from step 3.2)
```

**Before running:** edit `schema-prod.sql` to change the `frame_app` password from `change_me_in_prod` to a real password:
```bash
sudo nano /opt/frame/deploy/schema-prod.sql
# Line: CREATE USER IF NOT EXISTS 'frame_app'@'localhost' IDENTIFIED BY 'change_me_in_prod';
# Change to: IDENTIFIED BY 'your_real_password'
```
Then in Part 4.4, set `FRAME_DB_PASSWORD=your_real_password` in `frame-backend.service` to match — the backend connects as `frame_app`, so these two values MUST be identical.

> ⚠ `CREATE USER IF NOT EXISTS` does NOT change an existing user's password. If you ever need to rotate it later, run `ALTER USER 'frame_app'@'localhost' IDENTIFIED BY 'new_pw';` instead.

### 3.4 Enable services to auto-start on boot

```bash
sudo systemctl enable mariadb
sudo systemctl enable redis-server
sudo systemctl enable nginx
```

---

## Part 4: Build & transfer the app (from Windows)

### 4.1 Build on Windows

Open Git Bash on your Windows machine:
```bash
cd "C:/Users/tcmao/Desktop/newRepo/frame-backend"
export PATH="/c/Program Files/Java/jdk-21.0.11/bin:/d/apache-maven-3.9.16/bin:$PATH"
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"
mvn clean package -DskipTests
# → target/frame-backend-0.0.1-SNAPSHOT.jar

cd "C:/Users/tcmao/Desktop/newRepo/frame-frontend"
npm run build
# → dist/
```

### 4.2 Create directories + the `frame` service user on the server

```bash
# On the Ubuntu server:
# Create a dedicated system user for the backend service (matches User=frame in
# frame-backend.service). --system creates a no-login service account; --create-home
# gives it a home so systemd has a clean runtime identity.
sudo useradd --system --create-home --shell /usr/sbin/nologin frame

sudo mkdir -p /opt/frame/deploy /var/lib/frame/uploads /var/www/frame-frontend
# Own the app dirs as the frame service user (the jar + uploads must be readable by it)
sudo chown -R frame:frame /opt/frame /var/lib/frame
# Frontend is served by Nginx (www-data), so it owns the web root
sudo chown -R www-data:www-data /var/www/frame-frontend
```

### 4.3 Transfer files (from Windows Git Bash)

```bash
SERVER=tcmao@192.168.1.105   # replace with your VM's IP

# Backend jar
scp "C:/Users/tcmao/Desktop/newRepo/frame-backend/target/frame-backend-0.0.1-SNAPSHOT.jar" \
    $SERVER:/opt/frame/

# Frontend dist
scp -r "C:/Users/tcmao/Desktop/newRepo/frame-frontend/dist/"* \
    $SERVER:/var/www/frame-frontend/

# Deploy config files
scp "C:/Users/tcmao/Desktop/newRepo/deploy/"* \
    $SERVER:/opt/frame/deploy/

# schema-prod.sql (already in deploy/ but make sure)
scp "C:/Users/tcmao/Desktop/newRepo/deploy/schema-prod.sql" \
    $SERVER:/opt/frame/deploy/
```

### 4.4 Set production secrets (env vars)

`application-prod.yml` is **baked into the jar** (at `frame-backend/src/main/resources/application-prod.yml`) — it reads secrets from environment variables with placeholders, so you do NOT edit a file on the server. Instead, set the real values as `Environment=` lines in the systemd unit (the shipped `deploy/frame-backend.service` already has placeholder lines — replace them):

```bash
# On the server, BEFORE starting the service:
sudo nano /opt/frame/deploy/frame-backend.service
# Under [Service], replace the placeholder values:
#   Environment=FRAME_DB_PASSWORD=change_me_in_prod       ← the password you set in schema-prod.sql
#   Environment=FRAME_JWT_SECRET=...                     ← a long random string (≥32 chars)
#   Environment=FRAME_REDIS_PASSWORD=                    ← your Redis password, or blank if none
#   Environment=FRAME_CORS_ORIGINS=http://192.168.1.105  ← your VM's IP (the origin the browser uses)
```

**Important:** the `FRAME_DB_PASSWORD` here MUST match the password you set for `frame_app` in `schema-prod.sql` (Part 3.3). If they differ, the backend can't connect to MariaDB.

The four `frame_app` / `frame` / uploads / Nginx moving parts:
- `schema-prod.sql` creates DB user `frame_app@localhost` with a password → backend connects as `frame_app` (NOT root).
- `frame-backend.service` runs the jar as the Linux user `frame` → jar + `/var/lib/frame/uploads` must be owned by `frame`.
- `nginx.conf` serves `/var/www/frame-frontend` (owned by `www-data`) + proxies `/api/` → `127.0.0.1:8081`.
- `nginx-rate-limit.conf` declares the rate-limit zone at `http` context (see Part 6).

---

## Part 5: Deploy the backend (systemd)

### 5.1 Install the service

```bash
sudo cp /opt/frame/deploy/frame-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable frame-backend
sudo systemctl start frame-backend
```

### 5.2 Verify

```bash
sudo systemctl status frame-backend
# Should show "active (running)"

# Check the logs:
sudo journalctl -u frame-backend -f
# (Ctrl+C to exit the log stream)

# Test the API:
curl http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"pass1234"}'
# Should return a Result with the created user
```

**If it fails**, check the logs:
```bash
sudo journalctl -u frame-backend --no-pager -n 50
```

Common issues:
- **DB connection refused:** MariaDB not running, or wrong password → `sudo systemctl status mariadb`
- **Redis connection refused:** Redis not running → `sudo systemctl status redis-server`
- **Port 8081 in use:** `sudo ss -tlnp | grep 8081`

---

## Part 6: Deploy the frontend (Nginx)

### 6.1 Install the Nginx config

```bash
# The rate-limit ZONE must live at nginx `http` context, not in a site's `server`
# block (nginx -t rejects `limit_req_zone` there). Ubuntu's default nginx.conf
# includes conf.d/*.conf at the http level, so this snippet is picked up automatically:
sudo cp /opt/frame/deploy/nginx-rate-limit.conf /etc/nginx/conf.d/

# The site config (server block + the `limit_req` that applies the zone):
sudo cp /opt/frame/deploy/nginx.conf /etc/nginx/sites-available/frame-shop
sudo ln -sf /etc/nginx/sites-available/frame-shop /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default   # remove the default site
sudo nginx -t                                 # test config (should say "OK")
sudo systemctl reload nginx
```

If `nginx -t` reports `unknown "checkout" zone` → you forgot the `conf.d/` copy above. If it reports `"limit_req_zone" directive is not allowed here` → a zone declaration leaked into a `server` block.

### 6.2 Verify

From your **Windows browser**, open:
```
http://192.168.1.105
```
(replace with your VM's IP)

You should see the Frame Shop frontend. Register → Login as `admin` / `admin123` → open a flash sale → checkout.

---

## Part 7: Backup strategy (for critical data)

### 7.1 What to back up

| Data | Location | How |
|---|---|---|
| **MariaDB database** | `frame_db` | `mariadb-dump` |
| **Uploaded images** | `/var/lib/frame/uploads/` | `tar` |
| **Config files** | `/opt/frame/deploy/`, `/etc/systemd/system/frame-backend.service`, `/etc/nginx/sites-available/frame-shop` | `tar` |
| **The jar** | `/opt/frame/frame-backend-*.jar` | (rebuildable from source, but back up for quick restore) |

### 7.2 Create a backup script

```bash
# On the server:
sudo nano /opt/frame/backup.sh
```

Paste:
```bash
#!/bin/bash
# Frame Shop backup script — run via cron daily
BACKUP_DIR=/opt/frame/backups
DATE=$(date +%Y%m%d_%H%M%S)
mkdir -p "$BACKUP_DIR"

# 1. MariaDB database dump
mariadb-dump -u root -pYOUR_ROOT_PASSWORD frame_db > "$BACKUP_DIR/db_$DATE.sql"

# 2. Uploaded images
tar czf "$BACKUP_DIR/uploads_$DATE.tar.gz" -C /var/lib/frame uploads

# 3. Config files
tar czf "$BACKUP_DIR/configs_$DATE.tar.gz" \
    /opt/frame/deploy/ \
    /etc/systemd/system/frame-backend.service \
    /etc/nginx/sites-available/frame-shop \
    /opt/frame/frame-backend-0.0.1-SNAPSHOT.jar 2>/dev/null

# 4. Clean up backups older than 7 days
find "$BACKUP_DIR" -name "*_*" -mtime +7 -delete

echo "Backup complete: $BACKUP_DIR ($DATE)"
```

Make it executable + schedule it:
```bash
sudo chmod +x /opt/frame/backup.sh

# Run it manually to test:
sudo /opt/frame/backup.sh

# Schedule a daily backup at 2 AM:
sudo crontab -e
# Add this line at the bottom:
0 2 * * * /opt/frame/backup.sh >> /var/log/frame-backup.log 2>&1
```

### 7.3 Restore from backup

```bash
# Restore the database:
sudo mariadb -u root -p frame_db < /opt/frame/backups/db_20260817_020000.sql

# Restore uploaded images:
sudo tar xzf /opt/frame/backups/uploads_20260817_020000.tar.gz -C /var/lib/frame

# Restore configs:
sudo tar xzf /opt/frame/backups/configs_20260817_020000.tar.gz -C /

# Restart the backend:
sudo systemctl restart frame-backend
```

### 7.4 (Optional) Off-site backup — copy backups to your Windows machine

```bash
# From Windows Git Bash (run periodically or add to a script):
scp -r tcmao@192.168.1.105:/opt/frame/backups/ "C:/Users/tcmao/Desktop/frame-backups/"
```

---

## Quick reference: verify the deployed system

```bash
# 1. Backend running?
sudo systemctl status frame-backend

# 2. Nginx running?
sudo systemctl status nginx

# 3. MariaDB + Redis running?
sudo systemctl status mariadb redis-server

# 4. Firewall active?
sudo ufw status

# 5. API works?
curl http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 6. Frontend loads? (from Windows browser)
# Open: http://192.168.1.105

# 7. Backups exist?
ls -lh /opt/frame/backups/
```

---

## Troubleshooting

| Problem | Check | Fix |
|---|---|---|
| Can't SSH in | `sudo ufw status` (is 22 allowed?) | `sudo ufw allow 22/tcp` |
| Backend won't start | `sudo journalctl -u frame-backend -n 50` | Check DB password, Redis connection |
| Frontend shows blank page | Nginx config, `dist/` files in `/var/www/frame-frontend/` | `ls /var/www/frame-frontend/` — should have `index.html` + `assets/` |
| API returns 401 | JWT secret mismatch | Check `application-prod.yml` `frame.jwt.secret` |
| `502 Bad Gateway` from Nginx | Backend not running on :8081 | `sudo systemctl status frame-backend` |
| Checkout returns 503 "stock not initialized" | Admin hasn't opened the flash sale | Login as admin → POST /admin/sales/{productId} |

---

## Part 8: Two-instance distributed variant (Kylin V10 single-host)

> **Topology T1 — one host, two backend instances behind Nginx.** This section deploys the same app onto a **Kylin Linux Advanced Server V10 (Sword)** cloud host with **two backend instances** (ports 8081 + 8082) load-balanced by Nginx, sharing a single MariaDB (:13306) and Redis (:16379) — all on the same box. Use this instead of Parts 1–7 when the target is the Kylin server, not an Ubuntu VM.
>
> **Why two instances?** Horizontal scale on one host: Nginx round-robins `/api/` across `127.0.0.1:8081` and `127.0.0.1:8082`, so a slow request on one instance doesn't block the other. Both share the same MariaDB + Redis (and thus the same stock Lua gate, Stream, session, coins), so the system behaves as one logical app. Uploads share the local disk path `/var/lib/frame/uploads` — no MinIO/OSS in T1.
>
> **Hard constraint — do NOT disturb existing services.** The Kylin host already runs Nacos (8848/9848/9849/7848/9876), RocketMQ (10909/10911/10912), gse_agent (18167/60020), BOAgentService, titanagent, and SSH (22). **Never reuse these ports, DBs, or data dirs.** Our chosen ports deliberately avoid the entire in-use set.

### 8.1 Server facts & port plan

| Item | Value |
|---|---|
| OS | Kylin Linux Advanced Server V10 (Sword) — RHEL-family, uses `yum`/`dnf` |
| Host | `172.29.231.145`, login `root`, password `Bigdata_2023` |
| Resources | 8 cores, 22 GB RAM, 33 GB free disk |
| Package manager | `yum` (or `dnf` — both work on Kylin V10) |
| **In-use ports (AVOID)** | 22, 5005, 9876, 9848, 9849, 7848, 8848 (Nacos), 10909/10911/10912 (RocketMQ), 18167, 60020 (gse_agent) |
| **Our ports (chosen to avoid the above)** | backend 8081 + 8082; MariaDB 13306; Redis 16379; Nginx public 80 |
| Bind policy | MariaDB + Redis + both backends bind `127.0.0.1` only; Nginx :80 is the sole public entry |
| Uploads | local shared disk `/var/lib/frame/uploads` (both instances share the host filesystem) |

### 8.2 Install the runtime (yum, not apt)

Kylin is RHEL-family, so use `yum`/`dnf`, not `apt`. The package names also differ (e.g. `redis`, not `redis-server`; the service is `redis`, not `redis-server`).

```bash
# All commands run as root on the Kylin host (ssh root@172.29.231.145)
yum install -y java-21-openjdk-headless mariadb-server redis nginx
# (If yum is symlinked to dnf on your Kylin build, `dnf install ...` is equivalent.)

# Verify
java -version          # openjdk 21.x
mariadb --version      # 10.x or 11.x
redis-cli ping         # PONG (if redis started by default; if not, start it below)
nginx -v               # nginx version
```

### 8.3 Install OUR MariaDB on port 13306 (not the default 3306)

The default MariaDB port 3306 may collide with conventions on a shared host; we run our own instance on **13306** bound to `127.0.0.1` only. Configure the port before first start.

```bash
# 1. Drop a dedicated config that forces OUR port + bind address.
#    (Kylin's mariadb-server reads /etc/my.cnf.d/*.cnf — same include mechanism as RHEL.)
cat > /etc/my.cnf.d/frame.cnf <<'EOF'
# Frame Shop MariaDB — non-default port to avoid clashes on the shared Kylin host.
[mysqld]
port=13306
bind-address=127.0.0.1
EOF

# 2. Initialize data dir + start + enable (so it survives reboot).
mariadb-install-db --user=mysql --datadir=/var/lib/mysql
systemctl enable --now mariadb

# 3. Verify the port — MUST be 13306, NOT 3306.
ss -tlnp | grep 13306      # expect: 127.0.0.1:13306  (NOT 0.0.0.0, NOT 3306)

# 4. Secure it + set a root password (write the password down — used in 8.5).
mariadb-secure-installation
#   - Enter current root password: press Enter (none set yet)
#   - Set root password? Y → choose a strong password, WRITE IT DOWN
#   - Remove anonymous users? Y
#   - Disallow remote root login? Y
#   - Remove test database? Y
#   - Reload privilege tables? Y
```

### 8.4 Install OUR Redis on port 16379 (not the default 6379)

```bash
# 1. Force OUR port + bind address (avoid the default 6379 on the shared host).
cat > /etc/redis.conf <<'EOF'
# Frame Shop Redis — non-default port to avoid clashes on the shared Kylin host.
port 16379
bind 127.0.0.1
EOF

# 2. Start + enable (Kylin's service is `redis`, not `redis-server`).
systemctl enable --now redis

# 3. Verify.
redis-cli -p 16379 ping          # PONG
ss -tlnp | grep 16379            # expect 127.0.0.1:16379 (NOT 0.0.0.0)
redis-cli -p 16379 INFO server | grep redis_version   # note the version (see note below)
```

> **Redis version note:** if Kylin's Redis is < 6.2, the backend automatically falls back to the XPENDING+XCLAIM consumer-group path (instead of XAUTOCLAIM) — both are correct, no action needed. This does not affect functionality.

### 8.5 Create the database + app user + apply schema

The shipped `deploy/schema-prod.sql` creates database `frame_db` and user `frame_app@localhost` (password defaults to `change_me_in_prod` — **edit it before applying**). Apply the schema **as root** first (root has the privileges to CREATE DATABASE/USER); the backend then connects as `frame_app`.

```bash
# 1. First set a REAL password for frame_app in the schema file (don't ship the default).
#    Replace change_me_in_prod with a strong password — write it down (used in 8.7).
sed -i "s/IDENTIFIED BY 'change_me_in_prod'/IDENTIFIED BY 'YOUR_REAL_FRAME_APP_PW'/" \
    /opt/frame/deploy/schema-prod.sql

# 2. Apply the schema AS ROOT (root can CREATE DATABASE + CREATE USER; frame_app cannot yet).
mariadb -u root -p -P 13306 -h 127.0.0.1 < /opt/frame/deploy/schema-prod.sql
# Enter the MariaDB root password you set in 8.3.

# 3. Verify the DB + user exist on OUR port.
mariadb -u frame_app -p -P 13306 -h 127.0.0.1 -e "USE frame_db; SHOW TABLES;"
# Enter the frame_app password. Expect the list of tables (user, product, orders, ...).
```

> ⚠ `CREATE USER IF NOT EXISTS` does NOT change an existing user's password. If you ever need to rotate `frame_app`'s password later, run `ALTER USER 'frame_app'@'localhost' IDENTIFIED BY 'new_pw';` (on port 13306), then update `FRAME_DB_PASSWORD` in the systemd template (8.7) and restart both instances.

### 8.6 Build + transfer the app (from Windows)

The build steps are identical to the single-VM guide (Part 4.1) — same jar, same frontend `dist/`. Repeat here for convenience.

```bash
# On Windows (Git Bash):
cd "C:/Users/tcmao/Desktop/newRepo/frame-backend"
export PATH="/c/Program Files/Java/jdk-21.0.11/bin:/d/apache-maven-3.9.16/bin:$PATH"
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"
mvn clean package -DskipTests
# → target/frame-backend-0.0.1-SNAPSHOT.jar

cd "C:/Users/tcmao/Desktop/newRepo/frame-frontend"
npm run build
# → dist/
```

Create the directories + `frame` service user on the Kylin host, then transfer:

```bash
# On the Kylin host (as root):
useradd --system --create-home --shell /sbin/nologin frame
mkdir -p /opt/frame/deploy /var/lib/frame/uploads /var/www/frame-frontend
chown -R frame:frame /opt/frame /var/lib/frame
chown -R nginx:nginx /var/www/frame-frontend   # Kylin's nginx runs as user `nginx`, not `www-data`
```

```bash
# On Windows (Git Bash) — transfer to the Kylin host:
SERVER=root@172.29.231.145

# Backend jar
scp "C:/Users/tcmao/Desktop/newRepo/frame-backend/target/frame-backend-0.0.1-SNAPSHOT.jar" \
    $SERVER:/opt/frame/

# Frontend dist
scp -r "C:/Users/tcmao/Desktop/newRepo/frame-frontend/dist/"* \
    $SERVER:/var/www/frame-frontend/

# Deploy config files (schema-prod.sql, nginx.conf, nginx-rate-limit.conf)
scp "C:/Users/tcmao/Desktop/newRepo/deploy/"* \
    $SERVER:/opt/frame/deploy/
```

### 8.7 Create the systemd TEMPLATE unit (2 instances via %i)

The single-VM guide uses one flat `frame-backend.service`. Here we use a **systemd template unit** `frame-backend@.service` — the `@` + `%i` lets one unit file spawn both `frame-backend@8081` and `frame-backend@8082`, differing only by the port passed as `%i`. The env vars (DB/Redis host+port, JWT, CORS, SMS) are identical for both instances, so they live once in the template.

> **Port override:** `application-prod.yml` hardcodes `server.port: 8081`. To run a second instance on 8082, the template passes `--server.port=%i` on the java command line — `%i` expands to the instance name (8081 or 8082), so each instance binds its own port.

```bash
# On the Kylin host, create the template unit:
cat > /etc/systemd/system/frame-backend@.service <<'EOF'
[Unit]
Description=Frame Shop Backend instance %i (Spring Boot)
After=network.target mariadb.service redis.service

[Service]
Type=simple
User=frame
Group=frame
WorkingDirectory=/opt/frame
# %i = the instance name (8081 or 8082). --server.port overrides the hardcoded
# 8081 in application-prod.yml so each instance binds a distinct port.
# --server.address=127.0.0.1 binds loopback-ONLY so the backends are NOT publicly
# exposed on the cloud host (Nginx :80 is the sole public entry; it proxies to
# 127.0.0.1:808x). Spring Boot defaults to 0.0.0.0 — override it.
ExecStart=/usr/bin/java -jar /opt/frame/frame-backend-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=prod \
    --server.port=%i \
    --server.address=127.0.0.1
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

# --- Production secrets (set these to REAL values — don't commit them) ---
Environment=FRAME_DB_HOST=127.0.0.1
Environment=FRAME_DB_PORT=13306
Environment=FRAME_DB_PASSWORD=YOUR_REAL_FRAME_APP_PW
Environment=FRAME_REDIS_HOST=127.0.0.1
Environment=FRAME_REDIS_PORT=16379
Environment=FRAME_REDIS_PASSWORD=
Environment=FRAME_JWT_SECRET=change_me_to_a_long_random_base64_secret_at_least_256_bits
Environment=FRAME_CORS_ORIGINS=http://172.29.231.145
# Aliyun SMS — fill real values to send real SMS; provider=aliyun enables it.
Environment=FRAME_SMS_PROVIDER=aliyun
Environment=FRAME_SMS_AK_ID=YOUR_RAM_AccessKeyId
Environment=FRAME_SMS_AK_SECRET=YOUR_RAM_AccessKeySecret
Environment=FRAME_SMS_SIGN_NAME=YOUR_SIGN_NAME
Environment=FRAME_SMS_TEMPLATE_CODE=YOUR_TEMPLATE_CODE

[Install]
WantedBy=multi-user.target
EOF

# Reload systemd so it sees the new template, then enable + start BOTH instances.
systemctl daemon-reload
systemctl enable --now frame-backend@8081
systemctl enable --now frame-backend@8082

# Verify both are active.
systemctl status frame-backend@8081 frame-backend@8082
# Expect: both "active (running)". (If one fails with "port already in use",
# the other instance grabbed 8081 — confirm %i expands correctly via:
#   systemctl cat frame-backend@8082 | grep ExecStart)
```

**Set the real secrets before starting:** edit the four `YOUR_*` placeholders (`FRAME_DB_PASSWORD`, `FRAME_JWT_SECRET`, `FRAME_SMS_*`) in `/etc/systemd/system/frame-backend@.service`, then `systemctl daemon-reload && systemctl restart frame-backend@8081 frame-backend@8082`. The `FRAME_DB_PASSWORD` MUST match the `frame_app` password you set in `schema-prod.sql` (8.5). `FRAME_CORS_ORIGINS` is the origin the browser uses — the server's public IP `http://172.29.231.145`.

The env-var set per instance:
- `FRAME_DB_HOST=127.0.0.1`, `FRAME_DB_PORT=13306`, `FRAME_DB_PASSWORD` — both instances connect to the SAME MariaDB.
- `FRAME_REDIS_HOST=127.0.0.1`, `FRAME_REDIS_PORT=16379`, `FRAME_REDIS_PASSWORD` — both connect to the SAME Redis (shared stock gate + Stream + session).
- `FRAME_JWT_SECRET` — MUST be identical across both instances (else a token minted by 8081 is rejected by 8082).
- `FRAME_CORS_ORIGINS=http://172.29.231.145` — the browser origin Nginx serves from.
- `FRAME_SMS_PROVIDER=aliyun` + the four Aliyun SMS vars — enable real SMS.

### 8.8 Install Nginx + the upstream config

The shipped `deploy/nginx.conf` already declares the two-instance `upstream frame_backend { server 127.0.0.1:8081; server 127.0.0.1:8082; }` block, and every `proxy_pass` targets `http://frame_backend` (not a hardcoded port) — so Nginx round-robins across both instances automatically. `deploy/nginx-rate-limit.conf` declares the shared-memory rate-limit zone (must live at `http` context, not inside a `server`).

```bash
# On the Kylin host (as root):

# 1. The rate-limit ZONE (http context — Kylin's nginx.conf includes conf.d/*.conf at http level).
cp /opt/frame/deploy/nginx-rate-limit.conf /etc/nginx/conf.d/

# 2. The site config (server block + upstream). Kylin's default nginx.conf has no
#    sites-enabled/ include by default, so install the server block into conf.d/ too
#    (which IS included at http level — a `server` block is valid there).
cp /opt/frame/deploy/nginx.conf /etc/nginx/conf.d/frame-shop.conf

# 3. Test the config.
nginx -t
# Expect: "nginx: configuration file /etc/nginx/nginx.conf test is successful"

# 4. Start + enable Nginx (port 80 — the sole public entry).
systemctl enable --now nginx
```

If `nginx -t` reports `unknown "checkout" zone` → you forgot step 1 (the zone file). If it reports `"limit_req_zone" directive is not allowed here` → a zone declaration leaked into a `server` block (it must be at `http` context, i.e. in `conf.d/` only, not inside the server block).

### 8.9 Verify the distributed deployment

```bash
# 1. Both backend instances running?
systemctl status frame-backend@8081 frame-backend@8082
# Expect both "active (running)".

# 2. Each instance responds directly (sanity: both are alive + wired to DB/Redis).
curl -s http://127.0.0.1:8081/api/products | head -c 120
curl -s http://127.0.0.1:8082/api/products | head -c 120
# Both should return JSON product data.

# 3. Nginx load-balances /api/ across both instances?
curl http://172.29.231.145/api/products
# Should return product JSON (served by 8081 or 8082 round-robin).

# 4. Frontend loads via Nginx?
curl -s http://172.29.231.145/ | head -c 120
# Should return the index.html (SPA shell).

# 5. Both instances share the SAME Redis? (After admin opens a flash sale, the
#    stock:* key must be visible from a single Redis regardless of which instance
#    wrote it — proves they share the gate.)
redis-cli -p 16379 keys 'stock:*'
# After a flash sale is opened (POST /admin/sales/{productId}), expect a stock:<id> key.

# 6. Per-instance logs (if something fails).
journalctl -u frame-backend@8081 -n 50 --no-pager
journalctl -u frame-backend@8082 -n 50 --no-pager
```

**Browser smoke test (from Windows):** open `http://172.29.231.145` → register → login as `admin` / `admin123` → open a flash sale → checkout. The request may hit either instance; because they share MariaDB + Redis, the result is identical.

### 8.10 Operating the two instances

```bash
# Restart both instances (e.g. after updating the jar or a secret):
systemctl restart frame-backend@8081 frame-backend@8082

# Roll one instance at a time (zero-downtime: Nginx keeps routing to the other):
systemctl stop  frame-backend@8081   # Nginx retries 8082; 8081 down briefly
# ... update jar / config ...
systemctl start frame-backend@8081
systemctl stop  frame-backend@8082   # now 8081 serves all traffic during 8082's restart
systemctl start frame-backend@8082

# Update the backend jar (zero-downtime rolling):
#   1. scp new jar to /opt/frame/frame-backend-0.0.1-SNAPSHOT.jar
#   2. systemctl stop frame-backend@8081 && systemctl start frame-backend@8081
#   3. systemctl stop frame-backend@8082 && systemctl start frame-backend@8082
# (Each stop/start flips one instance while the other keeps serving.)

# Tails both instances' logs:
journalctl -u 'frame-backend@*' -f
```

### 8.11 Troubleshooting (distributed variant)

| Problem | Check | Fix |
|---|---|---|
| `frame-backend@8082` fails "port 8081 already in use" | `systemctl cat frame-backend@8082 \| grep ExecStart` — does `%i` show as 8082? | The `--server.port=%i` arg is missing from the template — re-create the unit (8.7) |
| One instance returns 500, the other works | `journalctl -u frame-backend@<port> -n 50` | Usually a secret typo — `FRAME_JWT_SECRET` or `FRAME_DB_PASSWORD` must be byte-identical across both instances |
| `502 Bad Gateway` from Nginx | Both backends down, or Nginx upstream mismatch | `systemctl status frame-backend@8081 frame-backend@8082`; confirm `nginx.conf` upstream lists `127.0.0.1:8081` + `127.0.0.1:8082` |
| MariaDB "connection refused" | Wrong port — backend using 3306 not 13306 | Confirm `FRAME_DB_PORT=13306` in the template unit; `ss -tlnp \| grep 13306` shows it listening |
| Redis "connection refused" | Wrong port — backend using 6379 not 16379 | Confirm `FRAME_REDIS_PORT=16379` in the template unit; `redis-cli -p 16379 ping` → PONG |
| Collided with an existing service (Nacos/RocketMQ/gse_agent) | `ss -tlnp` shows our port occupied by a non-Frame process | Re-check the in-use port list in 8.1 — our ports (8081/8082/13306/16379/80) were chosen to avoid them; if a port drifted, pick another free one and update the template + nginx upstream |
| Checkout 503 "stock not initialized" on one instance only | Admin opened the sale via the OTHER instance; both should see the same `stock:*` key | Confirm both instances point at the SAME Redis (`FRAME_REDIS_PORT=16379`); `redis-cli -p 16379 keys 'stock:*'` |

---

## Distributed variant — quick reference

```bash
# Both backends up?
systemctl status frame-backend@8081 frame-backend@8082

# Nginx + DB + Redis?
systemctl status nginx mariadb redis

# API via the load balancer?
curl http://172.29.231.145/api/products

# Frontend via Nginx?
curl -s http://172.29.231.145/ | head -c 120

# Both instances share Redis? (after a flash sale opens)
redis-cli -p 16379 keys 'stock:*'

# Our ports listening on 127.0.0.1 only (not 0.0.0.0 — backends must NOT be public)?
# Expect 127.0.0.1:8081, 127.0.0.1:8082, 127.0.0.1:13306, 127.0.0.1:16379.
ss -tlnp | grep -E '8081|8082|13306|16379'
```
