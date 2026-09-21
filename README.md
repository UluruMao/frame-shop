# Frame Shop · (Flash-Sale E-Commerce Platform)

A full-stack, **Taobao-seckill-model** e-commerce platform — limited-quantity flash sales
with many concurrent buyers, where **overselling is impossible by construction**. Built on a
clean Spring Boot + React template and grown into a complete storefront: product catalog,
flash-sale checkout, order lifecycle, reviews, a coins economy, live chat, and an admin
dashboard. Designed for **horizontal scale** — two backend instances behind Nginx sharing one
MariaDB + Redis, with stateless JWT auth.

> Originally an internship scaffold project (learn Java / Spring Boot / Maven / MariaDB /
> Redis / Linux deployment), the template layer is preserved under `frame-backend` +
> `frame-frontend`; the business platform is built on top of it.

---

## ✨ Highlights

- **Defense-in-depth flash-sale concurrency** — a Redis Lua gate (atomic check-all-then-decrement)
  → a Redis Stream buffer with a consumer group (at-least-once, crash-safe) → a MariaDB
  conditional `UPDATE ... WHERE stock >= qty` backstop. Overselling cannot happen even under
  10k-QPS bursts.
- **Horizontally scalable** — stateless JWT, shared Redis + MariaDB. Four areas that would
  silently break under N≥2 instances (orders queue, chat broker, admin presence, schedulers)
  are hardened via a Stream consumer group, Redis pub/sub relay, Redis-backed presence, and
  `SET NX EX` leader election.
- **Stateless auth + security** — HS256 JWT in the `Authorization` header (no server session),
  URL-matcher role rules, AOP operation logging, account lockout, SMS verification, rate
  limiting, parameterized SQL only.
- **Live chat** — STOMP over SockJS with per-user/admin sessions, admin round-robin
  assignment, persist-before-publish; multi-instance-safe via Redis pub/sub relay.
- **Coins economy** — daily-login + review-reward + refund-return earnings, FIFO spend at
  checkout with per-credit expiry snapshotting, expiry sweep.
- **Fully tested** — backend JUnit/Mockito/Awaitility, frontend Vitest/Testing Library, a
  hand-rolled Node e2e runner, a STOMP integration test, a load-test script.

---

## 🧱 Tech Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot **4.1.0**, Java **21**, MyBatis-Plus **3.5.17**, MariaDB, Redis |
| Auth | JWT (jjwt 0.12.x, HS256, stateless), Spring Security URL-matcher role rules |
| API docs | SpringDoc OpenAPI (Swagger UI at `/swagger-ui.html`) |
| Realtime | WebSocket / STOMP over SockJS (chat) |
| Frontend | React **18**, TypeScript **5.5**, Vite **5**, Tailwind **3.4** |
| State / HTTP | Zustand (persisted auth store), Axios (response-envelope unwrapping) |
| UI libs | lucide-react, recharts |
| Tests | JUnit + Mockito + Awaitility (backend), Vitest + Testing Library (frontend), Node e2e runner |
| Deploy | Nginx (load balancer + static SPA), systemd, MariaDB, Redis on Linux |

---

## 📂 Repository Layout

```
newRepo/
├── frame-backend/            Spring Boot backend (Java 21)
│   ├── pom.xml
│   ├── src/main/java/com/iflytek/frame/
│   │   ├── controller/       REST + @MessageMapping endpoints
│   │   ├── service/          business logic (interface + impl)
│   │   ├── common/           Result<T>, DistributedLock, Money, enums, RedisKeys
│   │   ├── entity/           JPA-style entities (extend BaseEntity)
│   │   ├── mapper/           MyBatis-Plus mappers (+ XML)
│   │   ├── security/         JwtUtil, JwtAuthFilter, SecurityConfig, RateLimitFilter
│   │   ├── aop/              OperationLogAspect (audit logging)
│   │   └── config/           MybatisPlus, Redis, SpringDoc, SMS, WebMvc configs
│   └── src/main/resources/
│       ├── application.yml           dev profile (HTTP, SQL logging, logging SMS stub)
│       ├── application-prod.yml       prod profile (env-var secrets, loopback binds)
│       ├── lua/stock_gate.lua         atomic check-and-decrement stock gate
│       ├── mapper/*.xml              custom SQL (all #{}-parameterized)
│       └── schema.sql                10-table schema
├── frame-frontend/           React + Vite + TS frontend
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── api/              Axios instance + per-domain API modules
│       ├── store/            Zustand auth store (persisted)
│       ├── router/           React Router v6 route tree
│       ├── layouts/          MainLayout, AdminLayout
│       ├── pages/            Login, Register, Products, Checkout, Orders, Reviews,
│       │                     Coins, Chat, Profile, Dashboard, admin/*
│       ├── components/       reusable UI (Card, Button, Field, StatusBadge, ...)
│       └── lib/              format helpers (price, uuid, cn)
├── e2e/                      hand-rolled Node fetch e2e runner + load test
├── deploy/                   Linux deployment: nginx.conf, systemd units, schema-prod.sql,
│                             rate-limit conf, COMPLETE-DEPLOYMENT-GUIDE.md
└── docs/                     architecture retrospective + phase specs/plans
```

---

## 🚀 Quick Start

### Prerequisites

| Tool | Frontend | Backend |
|---|---|---|
| Node.js 18+ (npm 9+) | ✅ required | — |
| JDK 21 | — | ✅ required |
| Maven 3.9.x | — | ✅ required |
| MariaDB (running) | — | ✅ required |
| Redis (running) | — | ✅ required |

### Frontend (runs immediately)

```bash
cd frame-frontend
npm install
npm run dev          # http://localhost:5173
```

### Backend

```bash
cd frame-backend
# 1. Create the DB + 10 tables:
mariadb -u root -p < src/main/resources/schema.sql
# 2. Set your DB password + JWT secret in src/main/resources/application.yml
#    (or via env vars: FRAME_DB_PASSWORD, FRAME_JWT_SECRET — see .env.example)
# 3. Run:
mvn spring-boot:run   # http://localhost:8081  (Swagger: /swagger-ui.html)
```

Demo login: **`admin` / `admin123`** (or register a new account).

> All secrets (DB password, JWT secret, Aliyun SMS credentials) are read from environment
> variables with safe placeholders baked into `application.yml` / `application-prod.yml`.
> Copy `.env.example` → `.env` for the SMS template; **never commit real credentials.**
> (The local `key.txt` / `seed-*.sql` scratch files are git-ignored.)

---

## 🧩 Core Features

| Area | What it does |
|---|---|
| **Catalog** | Keyword search, multi-criteria sort, product detail pages, image upload |
| **Flash sale** | Admin opens a sale → stock loaded into Redis → Lua-gated checkout |
| **Checkout** | 3-stage: form → `202 queued` → poll until persisted. Idempotency-key guarded |
| **Orders** | 9-state lifecycle (PENDING→PAID→SHIPPED→DELIVERED→COMPLETED, +CANCELLED/FAILED/REFUNDED), 30-min auto-expiry, refunds |
| **Reviews** | 3-D star ratings (quality/logistics/service), image upload, edit, unique-per-order |
| **Coins** | Daily-login + review-reward + refund-return; FIFO spend at checkout; 30-day per-credit expiry |
| **Chat** | WebSocket/STOMP per-user ↔ admin sessions, admin round-robin, persist-before-publish |
| **Profile** | Avatar, nickname, signature, phone rebind (SMS-verified), password change, self-delete |
| **Security** | JWT, lockout after 10 fails, SMS verification, captcha, rate limit, AOP op-log, SQL-injection-proof |
| **Admin** | Products, orders, chat inbox, dashboard analytics (bar/line/pie charts), flash-sale discount % |

---

## ⚙️ The Concurrency Model (the headline)

Limited-quantity products, many buyers at once — overselling impossible:

1. **Tier 1 — Redis Lua gate** (`lua/stock_gate.lua`): two-pass all-or-nothing atomic
   check-and-decrement. Shields MariaDB from the burst. Fail-closed on Redis outage (503).
2. **Tier 2 — Redis Stream + consumer group** (`StreamConsumerService`): the hot path `XADD`s
   and returns `202`; a consumer drains via `XREADGROUP`, durably persists, and `XACK`s
   **only after the DB transaction commits** — crash-before-commit leaves the message in the
   Pending Entries List for redelivery. Dead-consumer recovery via `XPENDING`+`XCLAIM`.
3. **Tier 3 — MariaDB conditional UPDATE** (`WHERE stock >= qty`): the final backstop.
4. **Idempotency** — `orders.idempotency_key` UNIQUE is the sole authority (DB constraint =
   atomic check-and-insert); Redis `SET NX` is only an optional pre-check.
5. **One-per-user** — `seckill:{pid}:{uid}` TTL set *after* durable persist; deleted on
   FAILED/CANCELLED so retry works.

See [`docs/superpowers/ARCHITECTURE-AND-RETROSPECTIVE.md`](docs/superpowers/ARCHITECTURE-AND-RETROSPECTIVE.md)
for the full architecture write-up.

---

## 🌐 Distributed Deployment

The backend runs as **two instances** (ports 8081 + 8082) behind Nginx, sharing one MariaDB
and one Redis. Four multi-instance hazards are hardened:

| Hazard | Fix |
|---|---|
| Orders queue lost on crash / race-pop | Stream consumer group (XREADGROUP / XACK-after-commit / XCLAIM recovery) |
| Chat messages stuck on one instance | Redis pub/sub relay ("fan-out, local delivery") |
| Admin presence per-JVM | Redis-backed presence (shared SET + per-admin counters) |
| Schedulers fire N× | Redis `SET NX EX` leader election per `@Scheduled` task |

**Deploy** (Ubuntu/Kylin V10) — full step-by-step in
[`deploy/COMPLETE-DEPLOYMENT-GUIDE.md`](deploy/COMPLETE-DEPLOYMENT-GUIDE.md):

```bash
# Build on your dev machine
cd frame-backend && mvn clean package -DskipTests    # → target/*.jar
cd ../frame-frontend && npm run build                # → dist/

# On the server: install JDK 21 + MariaDB + Redis + Nginx, apply schema-prod.sql,
# set secrets as systemd Environment= lines, then:
systemctl enable --now frame-backend@8081 frame-backend@8082   # template unit (%i = port)
systemctl enable --now nginx
```

All backends bind `127.0.0.1` only; Nginx :80 is the sole public entry. Ports are chosen to
avoid clashing with existing services on a shared host.

---

## 🧪 Testing

```bash
# Backend
cd frame-backend && mvn test

# Frontend
cd frame-frontend && npm test

# End-to-end (needs a running backend + seeded DB)
cd e2e && node run-tests.mjs
```

---

## 📜 License

Internship project permitted by mentor. All rights reserved to the author.
