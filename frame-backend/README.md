# frame-backend

Spring Boot 4.1.0 + Java 21 + MyBatis-Plus + MariaDB + JWT + SpringDoc template backend.
Part of the full-stack template (`frame-backend` + `frame-frontend`).

## Tech stack (versions pinned, verified against Maven Central)

| Dependency | Version | Notes |
|---|---|---|
| Spring Boot | 4.1.0 | parent POM |
| Java | 21 | records, pattern matching, virtual threads |
| MyBatis-Plus | 3.5.17 | `mybatis-plus-spring-boot4-starter` |
| MariaDB JDBC | managed by Spring Boot | runtime |
| Spring Security | starter | stateless JWT via custom filter |
| SpringDoc OpenAPI | 3.1.0 | `springdoc-openapi-starter-webmvc-ui` |
| jjwt | 0.12.6 | `jjwt-api` / `jjwt-impl` / `jjwt-jackson` |
| Lombok | managed | boilerplate reduction |

## Prerequisites

This backend **will not compile or run** until the toolchain is installed.

- **JDK 21** — [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21) (set `JAVA_HOME`, add to PATH)
- **Maven 3.9.x** — [download](https://maven.apache.org/download.cgi), add `bin` to PATH
- **MariaDB** (running) — [download](https://mariadb.org/download/), port 3306, remember the root password

Verify in a fresh terminal:
```bash
java -version      # 21.x
mvn -version       # Maven 3.9.x referencing JDK 21
mariadb -u root -p # connects
```

## First-time setup

1. **Create the database + demo table.** From this folder:
   ```bash
   mariadb -u root -p < src/main/resources/schema.sql
   ```
2. **Set your DB password + JWT secret** in `src/main/resources/application.yml`:
   ```yaml
   spring:
     datasource:
       password: YOUR_ROOT_PASSWORD
   frame:
     jwt:
       secret: REPLACE_WITH_A_LONG_RANDOM_BASE64_SECRET_AT_LEAST_256_BITS
   ```

## Run

```bash
mvn spring-boot:run
```
Backend starts on http://localhost:8081 (port 8081 because 8080 was occupied on the dev machine; change `server.port` in `application.yml` if you prefer 8080).

- Swagger UI: http://localhost:8081/swagger-ui.html  (use the **Authorize** button → paste the JWT from `/auth/login`)
- OpenAPI JSON: http://localhost:8081/v3/api-docs

## Demo API (token flow)

**Login** (public, no auth):
```bash
curl -X POST http://localhost:8081/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```
Response (wrapped in `Result`):
```json
{ "code": 200, "message": "success",
  "data": { "token": "eyJhbGciOi...", "tokenType": "Bearer", "expiresIn": 86400,
            "userId": 1, "username": "admin" } }
```
Demo credentials: `admin` / `admin123` (stub in `AuthServiceImpl` — swap for a real user table + BCrypt later).

**Use the token** on a protected endpoint:
```bash
TOKEN="eyJhbGciOi..."   # paste the token value
curl http://localhost:8081/products \
  -H "Authorization: Bearer $TOKEN"
```

**CRUD endpoints** (all require auth, all under `/products`):
| Method | Path | Description |
|---|---|---|
| GET | `/products` | paginated list (`?current=1&size=10`) |
| GET | `/products/{id}` | get one |
| POST | `/products` | create (`{name, price, stock, description}`) |
| PUT | `/products/{id}` | update |
| DELETE | `/products/{id}` | delete |

## Project structure

```
src/main/java/com/iflytek/frame/
├── FrameBackendApplication.java   entry point (@SpringBootApplication + @MapperScan)
├── config/        MybatisPlusConfig (pagination + auto-fill), SpringDocConfig, WebMvcConfig
├── common/        Result<T>, ResultCode, BaseEntity, PageResult
├── exception/     BusinessException, GlobalExceptionHandler (@RestControllerAdvice)
├── security/      JwtUtil, JwtProperties, JwtAuthenticationFilter, SecurityConfig, SecurityUser, CorsProperties
├── controller/     AuthController (login/me), DemoProductController (CRUD demo)
├── service/        AuthService, DemoProductService (+ impl/)
├── mapper/         DemoProductMapper (extends BaseMapper)
├── entity/         DemoProduct (extends BaseEntity) — has stock field for mutex demo
├── dto/            LoginRequest, LoginResponse, DemoProductDTO
└── util/           PageQuery, SecurityUtils
src/main/resources/
├── application.yml   MariaDB + MyBatis-Plus + JWT + SpringDoc (+ commented Redis)
├── schema.sql        demo DB + table + seed rows
└── mapper/DemoProductMapper.xml   (custom SQL goes here; base CRUD is in BaseMapper)
```

## Key design choices (read before extending)

- **Stateless JWT auth** — no server session; any instance can serve any request. This is deliberate, to support distributed deployment later. JWT goes in the `Authorization: Bearer` header.
- **Unified `Result<T>` envelope** — `{ code, message, data, timestamp }` on every endpoint. The frontend Axios interceptor unwraps `data` and surfaces `message` on failure.
- **Global exception handler** catches `BusinessException` (→400), bean-validation (→400 field list), auth (→401/403), and anything else (→500). Never let an exception reach the client as a raw stack trace.
- **MyBatis-Plus auto-fill** sets `createTime`/`updateTime` — don't set them by hand.
- **HikariCP** connection pooling is on by default (production-shaped DB access).
- **Extension points left intentionally:**
  - `application.yml` has a commented `spring.data.redis.*` block — uncomment + add `spring-boot-starter-data-redis` to `pom.xml` to enable Redis later (caching, distributed locks).
  - `DemoProductMapper.xml` shows a commented `decrementStock` statement that does a `where stock >= qty` conditional update — the DB-level mutex pattern for concurrency-safe stock decrements across multiple instances.
  - `application.yml` supports profiles (`--spring.profiles.active=prod`) so the same jar runs on a Linux server with different config.

## Build a deployable jar

```bash
mvn clean package
# produces target/frame-backend-0.0.1-SNAPSHOT.jar
java -jar target/frame-backend-0.0.1-SNAPSHOT.jar
```

## Run tests

```bash
mvn test
```
The single test (`FrameBackendApplicationTests`) verifies the Spring context loads. Add controller/service tests as you build features.
