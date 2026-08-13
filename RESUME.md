# RESUME — Full-Stack Template + Frame Shop design

> Last updated 2026-08-12 (end of day). Working directory: `C:\Users\tcmao\Desktop\newRepo`
> The template is DONE + verified. The application design (Frame Shop) is DONE + written. Next: review the spec, then write the implementation plan.

---

## WHERE WE ARE NOW (read this first)

### ✅ Done — the template (`frame-backend` + `frame-frontend`)
Both ends built, tested, and verified end-to-end on Windows:
- Frontend: `npm run build` ✅, `npm test` ✅ (2/2). Runs on http://localhost:5173.
- Backend: `mvn clean test` ✅ (context-loads). Live app serves login → JWT → protected CRUD against MariaDB with correct 401s. Runs on http://localhost:8081.
- Environment: JDK 21, Maven 3.9.16, MariaDB 12.3, **Redis 5.0.14.1** — all installed + on user PATH. `JAVA_HOME` set. `frame_db` created with seed data. Full details in the env-facts table below.

### ✅ Done — the application design ("Frame Shop")
A flash-sale storefront to be built ON TOP of the template, modeled on the Alibaba/Taobao seckill (秒杀) architecture. Design spec written + self-reviewed:
**`docs/superpowers/specs/2026-08-12-frame-shop-flash-sale-design.md`**
This is the previous Node/TS e-commerce demo rebuilt on the real stack, with the concurrency/queue/distributed pieces that were deferred now actually implemented.

### ⏸ NEXT — implementation plan written; awaiting execution choice
The design spec was reviewed (ultracode adversarial review, 22 findings, all fixed) and approved. **The implementation plan is written: `docs/superpowers/plans/2026-08-12-frame-shop.md`** (8 V1 tasks, bite-sized TDD steps, grounded in the actual template code). **Tomorrow's first action: choose execution mode (subagent-driven vs inline) and start Task 1 (data model + schema).** Linux deployment (§11a) is a separate follow-up plan after V1.

---

## Environment facts (permanently configured)

| Thing | Value |
|---|---|
| Backend port | **8081** (8080 taken by `zBox_client.exe` — don't touch) |
| Frontend port | 5173 (Vite dev) |
| MariaDB root password | `frame_dev` |
| DB name / table | `frame_db` / `demo_product` (2 seed rows) |
| Demo login (template) | `admin` / `admin123` |
| JDK path | `C:\Program Files\Java\jdk-21.0.11` |
| Maven path | `D:\apache-maven-3.9.16\bin` |
| MariaDB path | `C:\Program Files\MariaDB 12.3\bin` |
| Redis path | `C:\Program Files\Redis` (service on :6379, verified PONG + Lua + Streams) |

> **PATH note:** all on the *user* PATH in the registry. A **fresh terminal from the Start menu** resolves them. A terminal opened as a child of an already-open one inherits stale env — open a new terminal or sign out/in.

## How to start tomorrow

1. Open a **fresh** terminal (Start menu → Git Bash) so the PATH changes apply.
2. Verify the toolchain:
   ```bash
   java -version && mvn -version && mariadb --version && redis-cli ping
   ```
3. **First task of the day:** ask the user to review the design spec at
   `docs/superpowers/specs/2026-08-12-frame-shop-flash-sale-design.md`.
   On approval → invoke the `writing-plans` skill to turn the spec (esp. §12 build order) into a detailed implementation plan. Then begin build step 1 (data model + schema).
4. (Reference) To run the template end-to-end now:
   ```bash
   # Terminal 1 — backend
   cd "C:/Users/tcmao/Desktop/newRepo/frame-backend" && mvn spring-boot:run   # :8081
   # Terminal 2 — frontend
   cd "C:/Users/tcmao/Desktop/newRepo/frame-frontend" && npm run dev          # :5173
   ```

---

## The Frame Shop design — at a glance (full detail in the spec)

**What:** flash-sale storefront on the template. Limited-stock products, many concurrent buyers, overselling must be impossible.

**Concurrency model — Taobao seckill (defense in depth):**
- Tier 1 edge (Nginx/Vite) → Tier 2 app+Redis (the gate) → Tier 3 MariaDB (backstop).
- **Redis Lua script** = primary stock gate (atomic check-and-decrement, shields DB from burst). Verified working on Redis 5.0.14.1.
- **Redis Stream** (`orders:pending`) = the async buffer (缓冲); consumer drains at controlled rate → DB.
- **DB conditional UPDATE** (`WHERE stock >= qty`) = final reconciliation backstop (the commented `decrementStock` in `DemoProductMapper.xml` becomes real).
- One-per-user key + client idempotency key (dedup, Taobao-style). Fail-closed on Redis outage.

**Data model:** user (NEW — real JWT auth, replaces old shared-secret), product (from DemoProduct + is_flash_sale/flash_sale_at), orders (status enum PENDING/PAID/SHIPPED/CANCELLED), order_item (name/price snapshots). Money = integer cents everywhere. Cart stays client-side (ported from old project).

**Pom change still needed:** add `spring-boot-starter-data-redis` to `frame-backend/pom.xml` (the commented `spring.data.redis.*` in `application.yml` becomes active). Redisson NOT needed.

**Build order** (spec §12, 9 steps):
1. Data model + schema (user, product, orders, order_item) + seed
2. Real auth (register/login backed by `user` table) — replaces stub
3. Product CRUD + image upload (admin) + Redis-cached reads (**upload dir must be a config value, not a Windows path** — for Linux deploy)
4. Cart (frontend, ported) + checkout endpoint with **synchronous** DB conditional-update gate first (get it correct)
5. Add Redis Lua primary gate + Stream buffer + consumer (the Taobao model)
6. Payment (atomic `pending→paid` flip) + order views
7. Tests: unit → integration → **load/mutex test** (N concurrent checkouts for stock=5 → exactly 5 succeed, rest 409, final stock 0 — the artifact to show the mentor)
8. Frontend: port pages, add login/register + flash-sale UI
9. **Linux deployment** (mentor requirement, NOT optional): build jar + dist, provision Linux server, systemd + Nginx reverse proxy, verify end-to-end on Linux, run load/mutex test against deployed server; optional two instances behind Nginx to demo 分布式

**Mentor topics mapped:** 前后端分离 (template) · 分布式部署 incl. Linux deploy (stateless JWT + shared Redis, deployed behind Nginx) · 高并发/缓冲 (Lua gate + Stream) · 并发与互斥 (Lua + DB conditional UPDATE) · 数据库长期保存 (MariaDB vs SQLite).

---

## Non-obvious fixes applied during the template build (don't re-debug)

1. **MyBatis-Plus `PaginationInnerInterceptor` missing** — in 3.5.17 it lives in a separate `mybatis-plus-jsqlparser` module; the SB4 starter doesn't pull it transitively. Added `com.baomidou:mybatis-plus-jsqlparser:3.5.17` to pom.xml.
2. **`ObjectMapper` not a bean in test context** — `SecurityConfig` originally autowired `ObjectMapper` into the filter-chain method, breaking `@SpringBootTest` context load. Fixed by resolving it lazily from `ApplicationContext`.
3. **MariaDB GSSAPI/Kerberos auth failure** — passwordless root made the JDBC driver try Windows SSPI; fixed by setting root password (`frame_dev`).
4. **Backend port 8080** — taken by another app (`zBox_client.exe`); backend runs on **8081**, Vite proxy + all READMEs updated to match.

## Key files

| File | Why |
|---|---|
| `docs/superpowers/specs/2026-08-12-frame-shop-flash-sale-design.md` | the design spec (revised after adversarial review) |
| **`docs/superpowers/plans/2026-08-12-frame-shop.md`** | **the implementation plan — 8 V1 tasks, TDD, start here tomorrow** |
| `RESUME.md` | this file |
| `frame-backend/pom.xml` | backend deps (jsqlparser fix present; Redis starter still to add) |
| `frame-backend/src/main/resources/application.yml` | DB creds, JWT secret, port 8081, commented Redis block |
| `frame-backend/src/main/resources/schema.sql` | template DB + table + seed rows (flash-sale schema = build step 1) |
| `frame-backend/.../security/SecurityConfig.java` | JWT filter chain, CORS, public paths |
| `frame-backend/.../common/Result.java` | the response envelope every endpoint uses |
| `frame-frontend/src/api/request.ts` | Axios instance: token attach, Result unwrap, 401→login |
| `frame-frontend/src/store/useAuthStore.ts` | Zustand: token + user, persisted |
| `frame-frontend/vite.config.ts` | `/api` → `:8081` proxy |

`HANDOFF.md` and `prompt.txt` are the original template spec (source of truth for the scaffold). This `RESUME.md` is the current-state summary (template done + app designed).
