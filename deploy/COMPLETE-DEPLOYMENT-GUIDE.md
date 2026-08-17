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
# Line: CREATE USER ... IDENTIFIED BY 'change_me_in_prod'
# Change to: IDENTIFIED BY 'your_real_password'
```

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

### 4.2 Create directories on the server

```bash
# On the Ubuntu server:
sudo mkdir -p /opt/frame/deploy /var/lib/frame/uploads /var/www/frame-frontend
sudo chown -R tcmao:tcmao /opt/frame /var/lib/frame /var/www/frame-frontend
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

### 4.4 Set environment variables

Edit `application-prod.yml` on the server to set real passwords:
```bash
# On the server:
nano /opt/frame/deploy/application-prod.yml
# (If you copied it there — or edit it in frame-backend/src/main/resources/ before building)
```

Or set env vars directly in the systemd service:
```bash
sudo nano /etc/systemd/system/frame-backend.service
# Add under [Service]:
# Environment=FRAME_DB_PASSWORD=your_db_password
# Environment=FRAME_JWT_SECRET=your_long_random_secret
# Environment=FRAME_REDIS_PASSWORD=
```

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
sudo cp /opt/frame/deploy/nginx.conf /etc/nginx/sites-available/frame-shop
sudo ln -sf /etc/nginx/sites-available/frame-shop /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default   # remove the default site
sudo nginx -t                                 # test config (should say "OK")
sudo systemctl reload nginx
```

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
