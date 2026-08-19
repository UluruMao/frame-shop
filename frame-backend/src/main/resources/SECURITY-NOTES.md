# Security NFR notes (Phase A)

This document records how each security-relevant non-functional requirement
(NFR) is satisfied in the Phase A backend, and what is explicitly deferred.

## HTTPS全站加密

- **Dev**: a self-signed certificate is available via `server.ssl.*` in
  `application.yml`. It is **off by default** (`ssl.enabled: false`) so the
  build, `@SpringBootTest` integration tests, the e2e suite, and the Vite dev
  proxy all keep working over HTTP. To test HTTPS locally, set
  `ssl.enabled: true` and ensure `dev-keystore.p12` is on the classpath
  (generate it with the `keytool` command in
  [Phase A task-10](../../../.superpowers/sdd/2026-08-19-phase-a-foundation/task-10-brief.md)).
  The browser will warn on the self-signed cert — acceptable for dev.
- **Prod**: TLS terminates at Nginx (HTTP→HTTPS redirect, TLS 1.2+). The
  backend stays HTTP behind Nginx, so no keystore is needed in prod.

## 防SQL注入 (SQL injection prevention)

All DB access goes through MyBatis-Plus `BaseMapper` / `LambdaQueryWrapper`
(parameterized prepared statements). The custom mapper XML
(`mapper/OrderMapper.xml`, `mapper/ProductMapper.xml`) uses `#{}` placeholders
exclusively — **no `${}` string interpolation** (which would be the injection
vector) appears in any mapper. Verified by grep: no `SELECT`/`WHERE` built with
string concatenation on user input. No `@Select`/`@Where` annotations with
concatenated SQL exist in the codebase.

## 操作日志记录 (Operation logging)

`OperationLogAspect` (Spring AOP, Task 8) intercepts every `POST`/`PUT`/`DELETE`
controller method and writes a row to the `operation_log` table with: operator
ID, request method/URI, parameters, result status, and execution time. Sensitive
fields (passwords, tokens) are masked before persistence. The table carries
`BaseEntity` audit columns (`create_time`, `update_time`).

## 数据加密 (Data encryption)

- **Passwords**: BCrypt-hashed (`BCryptPasswordEncoder`) — never stored or
  logged in plaintext.
- **JWT**: HS256-signed with a 256-bit+ secret (`frame.jwt.secret`), stateless.
- **At-rest column encryption**: **out of scope** for this version (no
  transparent column encryption / field-level cipher). Noted for a future
  hardening pass.

## OAuth2.0 — DEFERRED

OAuth2.0 is **not** implemented in this version. The existing JWT-based auth
(username/password → JWT bearer token) is retained. OAuth2 (e.g. third-party
login via GitHub/WeChat) is deferred to a later phase per the design spec §10.
