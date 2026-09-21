# HANDOFF — Full-Stack Template Project (Spring Boot + React)

> Written 2026-08-12. Read this first in a fresh Claude Code session.
> Working directory for this project: `C:\Users\tcmao\Desktop\newRepo`

---

## 1. What this project is

A **full-stack template/scaffold project** for an internship at Iflytek. The mentor's new assignment (pivoting from the previous Node/TypeScript e-commerce demo to the real production stack used at the company): learn Java, Spring Boot, Maven, MySQL/MariaDB, Redis, and Linux deployment. His instruction: "scaffold a template first, then develop business features on top of it."

The full spec is in **`prompt.txt`** (same folder). Read it — it's the source of truth. Summary below.

### Backend (Spring Boot)
- Spring Boot **4.1.0**, Java **21**
- MyBatis-Plus **3.5.17** via starter `mybatis-plus-spring-boot4-starter`
- MariaDB (relational DB)
- JWT auth + token login
- SpringDoc OpenAPI (Swagger UI)
- Deliverables: multi-layer structure (controller/service/mapper/entity/dto/util/config), `application.yml` (MariaDB + MyBatis-Plus + JWT + SpringDoc), base entity, global exception handler, unified API response wrapper, JWT filter & security interceptor, sample CRUD + OpenAPI annotations, `pom.xml` with exact versions.

### Frontend (`frame-frontend`)
- React **18** + TypeScript **5.5**, Vite **5.0**
- Tailwind CSS **3.4**, lucide-react, recharts
- React Router **v6**, Zustand, Axios
- Vitest + React Testing Library
- Deliverables: Vite+React+TS skeleton named `frame-frontend`, Tailwind global setup, Axios interceptor (token attach + error handling), Zustand store template, React Router layout + basic pages, demo page (lucide icon + recharts chart), Vitest config + one component test, `package.json` with exact versions.

### General
- Backend and frontend in **separate folders**, clear naming.
- Versions **strictly match** the spec.
- Working demo on both ends (DB connection, API request, login token flow, page routing, chart rendering).
- README.md for **both** backend and frontend (setup/run/build).
- After scaffolding, **explain the project structure briefly** so business dev can begin immediately.

---

## 2. Environment state (verified 2026-08-12)

| Tool | Status | Notes |
|------|--------|-------|
| Node.js | ✅ v24.19.0 | Frontend runs immediately. |
| npm | ✅ 11.17.0 | |
| **JDK 21** | ❌ NOT installed | Backend cannot build/run until installed. |
| **Maven** | ❌ NOT installed | Needed for backend build/deps. |
| **MariaDB** | ❌ NOT installed | Backend needs it running for the DB demo. |
| Redis | ❌ Not installed | Optional for now — not in the spec, but mentor mentioned it. Defer. |
| Git | ✅ installed | |
| OS | Windows 11 | bash shell available (Git Bash). |

**Key implication:** Claude can scaffold ALL backend Java source + `pom.xml` + config as text files without Java/Maven installed. The frontend can run immediately with `npm install && npm run dev`. The backend won't compile/run until JDK 21 + Maven are installed and MariaDB is running. **Environment setup should be step 1 before trying to run the backend.**

---

## 3. Environment setup guide (do this first, in order)

### Step 3.1 — Install JDK 21

Recommended: **Eclipse Temurin 21** (free, widely used).

1. Go to https://adoptium.net/temurin/releases/?version=21
2. Select **Windows x64**, **.msi** installer.
3. Download and run it. During install, **check "Set JAVA_HOME variable"** and "Add to PATH".
4. Open a NEW terminal and verify:
   ```bash
   java -version
   javac -version
   echo $JAVA_HOME
   ```
   You should see `21.x.x`. If not, restart your terminal (PATH changes need a fresh shell).

> Alternatives: Oracle JDK 21 (https://www.oracle.com/java/technologies/downloads/#java21) also works. Avoid JDK 8/11/17 — the mentor specifically said JDK 21 (records, pattern matching, virtual threads, etc.).

### Step 3.2 — Install Maven

1. Go to https://maven.apache.org/download.cgi
2. Download **apache-maven-3.9.x-bin.zip** (use 3.9.9 or later; 3.8 also fine).
3. Extract to e.g. `C:\maven`.
4. Add Maven's `bin` to your PATH:
   - Search "Environment Variables" in Windows start menu → "Edit the system environment variables" → "Environment Variables…"
   - Under "User variables", find `Path`, click Edit, add a new line: `C:\maven\bin`
5. Open a NEW terminal and verify:
   ```bash
   mvn -version
   ```
   It should print the Maven version AND reference JDK 21.

> ⚠️ If `mvn -version` says "JAVA_HOME not found" or points to a different JDK, set the `JAVA_HOME` user variable to your JDK install path (e.g. `C:\Program Files\Eclipse Adoptium\jdk-21.x.x.x-hotspot`), then reopen the terminal.

### Step 3.3 — Install MariaDB

1. Go to https://mariadb.org/download/
2. Download the **Windows MSI** installer for the latest stable (e.g. 11.x).
3. Run it. During install:
   - Set a **root password** — remember it; it goes in `application.yml`.
   - Leave the default port **3306**.
   - Check "Use UTF8MB4 as the server default charset."
4. Finish install. The "MariaDB" service should now be running.
5. Verify from a terminal:
   ```bash
   mariadb -u root -p
   ```
   Enter your root password. You should get a `MariaDB [(none)]>` prompt. Type `exit` to leave.

> Alternatives: MySQL 8 works too (MariaDB is a MySQL fork, mostly compatible). The mentor said MariaDB specifically; use MariaDB.

### Step 3.4 — (Optional, defer) Redis

The mentor mentioned Redis, but it's **not in the template spec**. Skip for now. When you're ready:
- Windows: install via https://github.com/tporadowski/redis (community Windows port) or use WSL/Docker.
- Verify with `redis-cli ping` → `PONG`.

### Step 3.5 — Verify everything together

Open a fresh terminal and run all of:
```bash
java -version    # 21.x
mvn -version     # Maven 3.9.x referencing JDK 21
node --version   # v24
npm --version    # 11
mariadb -u root -p   # connects to DB
```
If all five pass, you're ready to build and run the backend.

---

## 4. Planned project structure (what Claude will scaffold)

```
C:\Users\tcmao\Desktop\newRepo\
├── HANDOFF.md                 ← this file
├── prompt.txt                  ← the spec (source of truth)
├── README.md                   ← top-level overview + how to run both ends
├── .gitignore
├── frame-backend\              ← Spring Boot backend
│   ├── pom.xml
│   ├── README.md
│   └── src\
│       ├── main\
│       │   ├── java\com\iflytek\frame\
│       │   │   ├── FrameBackendApplication.java   (main class)
│       │   │   ├── config\        (MyBatisPlusConfig, WebMvcConfig, CorsConfig, SpringDocConfig)
│       │   │   ├── common\       (Result<T> wrapper, ResultCode, BaseEntity, PageResult)
│       │   │   ├── exception\    (GlobalExceptionHandler, BusinessException)
│       │   │   ├── security\     (JwtUtil, JwtAuthFilter, SecurityConfig OR WebMvc interceptor)
│       │   │   ├── controller\   (demo: DemoProductController, AuthController)
│       │   │   ├── service\      (DemoProductService, AuthService + impls)
│       │   │   ├── mapper\       (DemoProductMapper extends BaseMapper)
│       │   │   ├── entity\       (DemoProduct extends BaseEntity)
│       │   │   ├── dto\          (LoginRequest, LoginResponse, DemoProductDTO)
│       │   │   └── util\         (PageQuery, etc.)
│       │   └── resources\
│       │       ├── application.yml
│       │       └── mapper\      (DemoProductMapper.xml, optional for MyBatis-Plus)
│       └── test\
│           └── java\com\iflytek\frame\
│               └── FrameBackendApplicationTests.java
│
└── frame-frontend\             ← React + Vite + TS
    ├── package.json
    ├── vite.config.ts
    ├── tsconfig.json
    ├── tailwind.config.js
    ├── postcss.config.js
    ├── index.html
    ├── README.md
    ├── src\
    │   ├── main.tsx
    │   ├── App.tsx               (router + layout)
    │   ├── index.css             (Tailwind directives)
    │   ├── api\
    │   │   ├── request.ts        (Axios instance + interceptors: token attach, 401 handling)
    │   │   ├── auth.ts           (login API call)
    │   │   └── product.ts        (demo CRUD API calls)
    │   ├── store\
    │   │   └── useAuthStore.ts   (Zustand: token, user, persist)
    │   ├── router\
    │   │   └── index.tsx         (route table)
    │   ├── layouts\
    │   │   └── MainLayout.tsx    (nav + <Outlet/>)
    │   ├── pages\
    │   │   ├── Login.tsx          (demo: calls login, stores token)
    │   │   ├── Dashboard.tsx      (demo: recharts chart + lucide icons)
    │   │   └── Products.tsx      (demo: list from API)
    │   ├── components\            (shared UI)
    │   └── tests\
    │       └── Dashboard.test.tsx (one component test)
    └── vitest.config.ts
```

### Key wiring decisions baked into the plan
- **Backend layering:** controller → service (interface + impl) → mapper → entity. DTOs separate API shape from DB shape. `Result<T>` wraps every response as `{ code, message, data }`.
- **Auth:** `JwtUtil` creates/validates JWTs. `JwtAuthFilter` (a Spring `HandlerInterceptor` is the lighter approach; Spring Security filter if using Spring Security) reads the `Authorization: Bearer <token>` header and sets the user context. Login returns the JWT; frontend stores it in Zustand + localStorage and attaches it via the Axios interceptor.
- **MyBatis-Plus:** `BaseMapper<T>` gives CRUD for free; the sample `DemoProductMapper` extends it. `BaseEntity` holds `id`, `createTime`, `updateTime` with auto-fill via a MyBatis-Plus `MetaObjectHandler`.
- **Global exception handling:** `@RestControllerAdvice` catches `BusinessException` (→ 400 with its message), validation errors (→ 400 field details), and `Exception` (→ 500 generic), all wrapped in `Result`.
- **Frontend Axios interceptor:** request interceptor attaches `Authorization` header from the auth store; response interceptor catches 401 → clear token + redirect to login, and unwraps `data` from the `Result` envelope (or surfaces `message` as an error).
- **CORS:** backend allows the Vite dev origin (`http://localhost:5173`); the Axios base URL is `/api` with a Vite proxy to `:8080` to avoid CORS in dev.

### Version pinning (strict, from spec)
- Backend: Spring Boot `4.1.0`, Java 21, `mybatis-plus-spring-boot4-starter` (3.5.17), MariaDB JDBC driver, SpringDoc (version compatible with Spring Boot 4 — ⚠️ **verify SpringDoc support for SB 4.1.0**; if the SB4-specific artifact isn't available yet, use the closest working version and note it in the README), `jjwt` (0.12.x) for JWT.
- Frontend: react `18.3.1`, typescript `5.5.x`, vite `5.0.x`, tailwindcss `3.4.x`, lucide-react, recharts, react-router-dom `6.x`, zustand, axios, vitest + @testing-library/react.

---

## 5. How to resume in a fresh Claude Code session

1. Open a terminal in `C:\Users\tcmao\Desktop\newRepo`.
2. Run `claude` (or `claude -c` if a session exists).
3. Say: **"Read HANDOFF.md and prompt.txt, then scaffold the full-stack template (backend + frontend) as specified. Environment: JDK 21 + Maven + MariaDB not yet installed (Node is). Scaffold all source files; I'll install the toolchain to run the backend. Start with the backend, then frontend."**
4. After scaffolding, ask Claude to **explain the structure** (the spec requires a brief explanation so you can start business dev).

If you've already done the environment setup (Section 3), tell Claude that so it can `mvn`/`npm install` and verify builds.

---

## 6. The mentor's broader picture (DEVELOPMENT REQUIREMENT for this repo — guide the build, not a committed doc)

This section captures the mentor's broader intent so the template is built in a way that supports the *next* phases. **Do not commit this as a permanent repo doc** — fold the relevant guidance into the build decisions, and use the rest as context for future feature development.

### Discussion topics he raised (be ready to answer each in 2-3 sentences; the template should let you *demonstrate* them)

- **Front-end/back-end separation (前后端分离):** our previous project was already structured this way (React SPA ↔ Express API over JSON). This new template continues that separation — the frontend is a static SPA that talks to the backend purely over HTTP/JSON; it never touches the DB. When explaining the template, point this out explicitly.
- **Distributed deployment (分布式部署):** splitting FE/BE onto separate servers behind a load balancer. The template should be **stateless** (no in-memory session — JWT in a header is stateless, so any backend instance can serve any request). This is *why* the template uses JWT rather than server-side sessions.
- **High concurrency + buffering (高并发/缓冲):** horizontal scaling, Redis caching in front of the DB, message queues (Kafka/RabbitMQ) as buffers, rate limiting. The template doesn't implement these, but it should be structured so they're addable: a `service` layer that's easy to cache, a clean seam where a queue producer would go. When building, leave clear extension points and note them.
- **Concurrency & mutex (并发与互斥):** the previous project's stock-decrement/payment used **database-level conditional updates** as the mutex (`where stock≥qty` / `status='pending'`). In the Spring Boot template, MyBatis-Plus `update` with a `where` wrapper does the same. Java also has `synchronized`, `ReentrantLock`, `Atomic*` for in-process locking. Be ready to explain *why* DB-level locking is preferred for multi-instance correctness.
- **Long-term DB preservation (数据库长期保存):** the previous project used SQLite (one fragile file). MariaDB + regular backups (`mariadb-dump`) + replication is the production answer. The template should use MariaDB properly (UTF8MB4, proper connection pool — HikariCP comes with Spring Boot by default) so it's production-shaped from the start.
- **Java:** the production stack at Iflytek is Java + Spring Boot + MySQL/MariaDB + Redis + Maven. This template is the foundation for learning and demonstrating all of it.

### His new build tasks (this template is the foundation for every one of them)

1. Learn JDK 21 + Spring Boot ← **this template is step 1**
2. Write a small service + deploy to Linux ← develop a business feature on this template, then deploy
3. Connect MySQL/MariaDB + Redis ← this template wires MariaDB; add Redis later as a business feature
4. Understand different middleware's advantages ← learn by doing, one feature at a time
5. Scaffold a template first, develop on top ← **this template IS that scaffold**
6. Learn Maven and Git ← Maven is wired into this template's `pom.xml`; Git you know

### Deferred (not in this template spec, but mentioned by mentor — add later as business features)
- Redis integration (caching, session, distributed locks)
- Linux server deployment (build the jar, run on a Linux VM/WSL, systemd, Nginx reverse proxy)
- Deeper middleware comparisons (when to use Redis vs MariaDB vs a queue)

### Build guidance derived from the above
When scaffolding, make these choices so the template supports the future phases:
- **Stateless auth (JWT in header)** — enables distributed deployment later.
- **Clean service layer** — so caching/queueing can be slotted in without rewriting controllers.
- **HikariCP connection pooling** — default with Spring Boot; leave it on for production-shaped DB access.
- **Config externalized to `application.yml`** — so the same jar can run on a Linux server with a different profile (`--spring.profiles.active=prod`).
- **Leave a `redis` config section commented out** in `application.yml` + a stub config class, so adding Redis later is a one-step uncomment.

---

## 7. Related prior work (for reference, not to modify)

- Previous project: `C:\Users\tcmao\Desktop\ecommerceProject` (Node/TS e-commerce). Its `docs/PROGRESS.md` has the full history. This new repo is a **separate, independent project** — do not couple them.
