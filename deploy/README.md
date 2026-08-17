# Frame Shop Deployment Guide

## Prerequisites
- Ubuntu 22.04+ (VM or cloud instance)
- SSH access to the server

## Step 1: Install runtime on the Linux server

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk-headless mariadb-server redis-server nginx
```

Verify:
```bash
java -version          # 21.x
mariadb --version
redis-cli ping         # PONG
nginx -v
```

## Step 2: Build locally (on Windows)

```bash
cd C:/Users/tcmao/Desktop/newRepo/frame-backend
mvn clean package -DskipTests    # → target/frame-backend-0.0.1-SNAPSHOT.jar

cd ../frame-frontend
npm run build                    # → dist/
```

## Step 3: Set up the database

```bash
# On the Linux server, create the DB + tables + seed data + app user
sudo mariadb < /opt/frame/deploy/schema-prod.sql
```

**Important:** change the `frame_app` password in `schema-prod.sql` and `application-prod.yml` before running in production.

## Step 4: Transfer files to the server

```bash
# From Windows (Git Bash)
scp frame-backend/target/frame-backend-0.0.1-SNAPSHOT.jar user@server:/opt/frame/
scp -r frame-frontend/dist/* user@server:/var/www/frame-frontend/
scp deploy/* user@server:/opt/frame/deploy/
```

## Step 5: Configure the backend

```bash
# On the server
sudo mkdir -p /opt/frame /var/lib/frame/uploads /var/www/frame-frontend
sudo chown -R frame:frame /opt/frame /var/lib/frame /var/www/frame-frontend

# Set environment variables (or hardcode in application-prod.yml)
export FRAME_DB_PASSWORD=your_db_password
export FRAME_JWT_SECRET=your_long_random_secret
export FRAME_REDIS_PASSWORD=your_redis_password

# Install the systemd service
sudo cp /opt/frame/deploy/frame-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable frame-backend
sudo systemctl start frame-backend
sudo systemctl status frame-backend
```

## Step 6: Configure Nginx

```bash
sudo cp /opt/frame/deploy/nginx.conf /etc/nginx/sites-available/frame-shop
sudo ln -s /etc/nginx/sites-available/frame-shop /etc/nginx/sites-enabled/
sudo nginx -t          # test config
sudo systemctl reload nginx
```

## Step 7: Verify end-to-end

```bash
# Register a user
curl -X POST http://localhost/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"pass1234"}'

# Login as admin
curl -X POST http://localhost/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# Open a flash sale (as admin)
curl -X POST http://localhost/api/admin/sales/1 \
  -H "Authorization: Bearer <admin-token>"

# Checkout
curl -X POST http://localhost/api/checkout \
  -H "Authorization: Bearer <user-token>" \
  -H "Content-Type: application/json" \
  -d '{"items":[{"productId":1,"quantity":1}],"customerName":"x","customerEmail":"x@x","shippingAddress":"y","idempotencyKey":"k1"}'
```

## Step 8 (optional): Distributed variant

To demonstrate 分布式 (FE/BE on separate servers behind a load balancer):

1. Provision a second Linux server.
2. Install JDK 21 + the jar on the second server.
3. Point the second server's `application-prod.yml` at the SAME MariaDB + Redis (shared state).
4. Change Nginx's `proxy_pass` from `127.0.0.1:8081` to the second server's IP.
5. Or: run two backend instances and use Nginx `upstream` to load-balance between them.

Stateless JWT + shared Redis/MariaDB means load-balancing "just works."
