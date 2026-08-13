# Full-Stack Template (Spring Boot + React)

A ready-to-develop full-stack template: a Spring Boot backend (`frame-backend`) and a
React + Vite frontend (`frame-frontend`), cleanly separated. The frontend talks to the
backend purely over HTTP/JSON — it never touches the database (前后端分离). Auth is
stateless JWT, so either side can be scaled/redeployed independently.

This scaffold is the foundation for business-feature development: learn the stack, then
add real features on top without reconfiguring the basics.

## Repository layout

```
newRepo/
├── frame-backend/    Spring Boot 4.1.0 + Java 21 + MyBatis-Plus + MariaDB + JWT + SpringDoc
└── frame-frontend/   React 18 + TypeScript + Vite 5 + Tailwind + Router + Zustand + Axios
```

Each module has its own `README.md` with full setup/run/build instructions.

## Quick start

### Frontend (runs immediately — Node is installed)
```bash
cd frame-frontend
npm install
npm run dev          # http://localhost:5173
```

### Backend (requires the toolchain — see below)
```bash
cd frame-backend
# 1. create the DB + demo table:
mariadb -u root -p < src/main/resources/schema.sql
# 2. set your DB password + JWT secret in src/main/resources/application.yml
# 3. run:
mvn spring-boot:run   # http://localhost:8081  (Swagger: /swagger-ui.html)
```

Demo login: `admin` / `admin123`.

## Prerequisites

| Tool | Frontend | Backend |
|---|---|---|
| Node.js 18+ (npm 9+) | ✅ required | — |
| JDK 21 | — | ✅ required |
| Maven 3.9.x | — | ✅ required |
| MariaDB (running) | — | ✅ required |

**The backend will not compile or run until JDK 21 + Maven + MariaDB are installed.** See
`frame-backend/README.md` for step-by-step toolchain install links. The frontend builds and
runs with Node alone — start there to see the UI immediately.

## What the template demonstrates

- **Database connection** — MariaDB via HikariCP; run `schema.sql` for the demo table.
- **API request** — Axios → Vite proxy → Spring Boot; every response wrapped in `Result<T>`.
- **Login token flow** — `/auth/login` returns a JWT; frontend stores it (Zustand +
  localStorage), attaches it as a `Bearer` header; `/auth/me` + `/products` require it.
- **Page routing** — React Router v6 with a protected layout (`/login`, `/dashboard`, `/products`).
- **Chart rendering** — recharts bar chart + lucide icons on the Dashboard.
- **Sample CRUD** — `DemoProduct` (entity → mapper → service → controller) with OpenAPI docs.
- **Global exception handling** — `BusinessException`, validation, auth, and generic errors
  all mapped to the `Result<T>` envelope.

## Where to extend

- **Backend:** add entities/services/controllers following the `DemoProduct` pattern
  (extend `BaseEntity`, a `BaseMapper`, a service interface + impl). Custom SQL goes in
  `src/main/resources/mapper/*.xml`. Redis is stubbed (commented `spring.data.redis.*` in
  `application.yml`) — uncomment + add the starter to enable it later.
- **Frontend:** add API modules in `src/api/`, pages in `src/pages/`, register routes in
  `src/router/index.tsx`. Reuse the Axios instance — the token is attached automatically.
