# Design — Frontend Beautification + Component Reuse + Style Unification (and backend reuse consolidation)

**Date:** 2026-08-25
**Origin:** Mentor instruction: "页面美化，组件复用，风格统一" + learn cloud deployment to `172.29.231.145`.
**Scope decision (user):** Frontend polish first, then deploy the improved app. Consistency-pass ambition (clean/minimal, unify on the polished newer-dialect look — no new visual identity).
**Constraint (user, clarified):** "已有的东西不要动" means **don't disturb existing data/services running on the cloud server** (change ports on conflict) — it does **not** mean freeze the project source. We freely edit the frontend and refactor the backend.

---

## 1. Problem statement

The frontend has two coexisting style dialects and hand-copied patterns; the backend has shared kits (`Result`, `PageResult`, `BaseEntity`, `SecurityUtils`, `BusinessException`) that are used well, but residual duplicated logic that a shared helper would collapse.

### 1.1 Frontend — verified duplication (file:line)

- **Input class string rewritten in 6+ files:** `w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500` — `Login.tsx:51,65`, `Profile.tsx:118` (`inputClass`), `Checkout.tsx:464` (`Field`), plus `Register.tsx`, `ForgotPassword.tsx`.
- **`STATUS_COLORS` (9-status badge color map) duplicated verbatim** in `Checkout.tsx:11-21` and `Orders.tsx:18-28` (with two divergent color values for the same statuses — e.g. `PAID` is `green` in Checkout but `blue` in Orders).
- **`formatPrice` defined in `Products.tsx:8` and imported by page files** `Checkout.tsx:8`, `Orders.tsx:16`. A page file acting as a shared util. Two pages (`Products.tsx:81`, `ProductDetailPage.tsx:77`) do `¥{(priceInCents/100).toFixed(2)}` inline instead of using it.
- **Card shell `bg-white border border-slate-200 rounded-lg p-4` hand-written ~15×** across `Checkout.tsx`, `Orders.tsx`, `Coins.tsx`, `Dashboard.tsx`, `Profile.tsx`.
- **`PageHeader` (icon + title) repeated** in `Checkout.tsx:431-438`, `Orders.tsx:191-204`, `Coins.tsx:32-35`, `Profile.tsx:122-125`.
- **Older dialect (`Products.tsx`, `ProductDetailPage.tsx`):** `border p-2 rounded` inputs (no focus ring), raw unstyled `<select>` (`Products.tsx:56,60`), `text-gray-500` (vs newer `text-slate-500`), buttons with no `hover`/`rounded-md` (`ProductDetailPage.tsx:81`).

### 1.2 Backend — verified duplication (file:line, from audit)

1. **Redis key strings hand-built across 11 files.** ~45 concatenations; 11 key-prefix literals duplicated (e.g. `"order_status:"` in `OrderServiceImpl.java:62` + `CheckoutServiceImpl.java:160` + `StreamConsumerService.java:280,303,354,375,384`; `"seckill:"` in 3 files; `"stock:"` in 2 files). Only `OrderServiceImpl`/`LoginAttemptServiceImpl` extract local constants.
2. **Redis-down tolerance wrapper copy-pasted 6× in `ProductServiceImpl.java:53-57,65-69,108-112,129-131,146-154`** with one *divergent* fail-closed policy in `CheckoutServiceImpl.java:137-141`. Two inconsistent strategies, no shared handler.
3. **Ownership guard `o == null || !o.getUserId().equals(userId) → NOT_FOUND` repeated 6× in `OrderServiceImpl.java:74,96,123,144,160,189`** (+1 at `:268`).
4. **`findByUsername(SecurityUtils.currentUsername())` + id-match re-fetch dance repeated in 4 services** (`PhoneRebindServiceImpl.java:39-42,58-61`, `ProfileServiceImpl.java:31-34`, `PasswordServiceImpl.java:27-31`).
5. **`try{insert}catch(DataIntegrityViolationException)` idempotency pattern hand-rolled 5×** (`CoinServiceImpl.java:59-64,88-93,223-227`, `ChatServiceImpl.java:40-50`, `StreamConsumerService.java:273-290`).
6. **`new Page<>()` + `PageResult.of(total,current,size,records)` 3-line tail duplicated 6×** (`AdminOrderServiceImpl.java:40,70`, `OrderServiceImpl.java:82`, `CoinServiceImpl.java:265`, `ProductServiceImpl.java:123`, `ChatServiceImpl.java:96`).
7. **Price×qty subtotal loop duplicated** (`CheckoutServiceImpl.java:150-154` == `CoinServiceImpl.java:113-115`); `COINS_PER_YUAN=100` private to `CoinServiceImpl.java:38`, re-derived implicitly in `StreamConsumerService.java:267-270`.
8. **Raw `throw new RuntimeException(e)` (loses business code) in 2 places** (`CheckoutServiceImpl.java:192`, `StreamConsumerService.java:388`) where `ReviewServiceImpl.java:165` correctly throws `BusinessException` for the same Jackson failure.
9. **Pagination: 3 controllers bypass `PageQuery`** (`AdminOperationLogController.java:25-31` builds `new Page<>()` + `LambdaQueryWrapper` inline in the controller; `AdminChatController.java:25-26`, `ChatController.java:24-25` use manual `@RequestParam page/size`).
10. **`ProductServiceImpl.page` (`:76-103`) re-derives `keywords`/`sortOrderSql`** logic that `PageQuery.java:45,49` already documents as derived fields.
11. **DTO boilerplate:** `phone`+`@NotBlank` in 4 DTOs, `code`+`@NotBlank` in 3 (`RegisterRequest`, `ForgotPasswordRequest`, `RebindConfirmRequest`, `SendCodeRequest`); `RebindVerifyOldRequest.java:6-9` has no validation at all. `validPassword`/`validUsername` static checks duplicated across 3 services.

---

## 2. Design principles

- **Reuse is the mechanism that enforces unification.** A shared kit, applied everywhere, structurally prevents drift — not just by convention.
- **Behavior-preserving.** Backend refactors extract → reuse, keeping the API contract byte-identical. The deployed cloud server is unaffected. Anything that *would* change behavior/API is flagged and skipped (logged for user review), per the autonomy directive.
- **No new visual identity.** Unify on the existing polished newer-dialect look (slate borders, brand accent, focus rings, hover states). No gradients, no theme overhaul.
- **Each unit small, one purpose.** Every new component/helper is independently understandable and testable.

---

## 3. Frontend design — shared `ui/` kit + `lib/`

New folder `src/components/ui/`, plus `src/lib/format.ts`. Each component is a single-purpose, presentational React component. The existing `@/*` alias (`tsconfig.json:27`, `vite.config.ts:16`) imports them. `verbatimModuleSyntax` is on → type-only imports use `import type`.

### 3.1 The kit

| Component | File | Replaces | Notes |
|---|---|---|---|
| `Button` | `ui/Button.tsx` | ~12 hand-written buttons | `variant: primary\|secondary\|danger\|ghost`, `size: sm\|md`, `loading?: boolean`, `icon?`. Canonical `bg-brand-600 hover:bg-brand-700 rounded-md` etc. |
| `Input` | `ui/Input.tsx` | the input class string ×6+ | Canonical focus-ring class. `error?: string`. |
| `Field` | `ui/Field.tsx` | Login/Checkout/Profile label+input | `<Field label id ...>` = label + `Input`. |
| `Select` | `ui/Select.tsx` | raw `<select>` in `Products.tsx:56,60` | Styled to match `Input`. Fixes the biggest visual eyesore. |
| `Card` | `ui/Card.tsx` | card shell ×15 | `<Card>` + compound `Card.Header`/`Card.Body`. |
| `Badge` | `ui/Badge.tsx` | colored pill spans | generic `color` prop. |
| `StatusBadge` | `ui/StatusBadge.tsx` | duplicated `STATUS_COLORS` maps | Owns the **single** 9-status color map. Resolves the Checkout-vs-Orders color divergence (pick one canonical mapping — the Orders `PAID=blue`/`SHIPPED=indigo` set, since it's the richer set). |
| `PageHeader` | `ui/PageHeader.tsx` | icon+title ×4 | `icon` + `title` + optional `subtitle`/`count`. |
| `EmptyState` | `ui/EmptyState.tsx` | `暂无数据` text | `icon` + `title` + optional action. |
| `Spinner` | `ui/Spinner.tsx` | `加载中…` text | `Loader2 animate-spin` (existing pattern in `Checkout.tsx:338`). |
| `Skeleton` | `ui/Skeleton.tsx` | (new) | Loading placeholder blocks. |

### 3.2 Shared utils — `src/lib/`

- `lib/format.ts` — `formatPrice(cents: number): string` (`$12.99` style, kept identical to current) + `cn(...classes)` (conditional class merger; trivial, no dep added — avoids pulling `clsx`/`tailwind-merge`).
- Moves `formatPrice` out of `Products.tsx:8` (a page file) into `lib/` — all importers (`Checkout`, `Orders`) and the two inline sites (`Products:81`, `ProductDetailPage:77`) switch to `@/lib/format`.

### 3.3 Page sweep (the unification)

Every page switches its hand-written controls to the kit. Per page:

- **Products.tsx** — biggest lift: raw `<input>`+`<select>` → `Input`/`Select`/`Button`; product cards → `Card` with image aspect-ratio + hover lift; `formatPrice` from `lib`; loading → `Spinner`/`Skeleton`; empty → `EmptyState`. Mixes `text-gray-*` → `text-slate-*`.
- **ProductDetailPage.tsx** — price `formatPrice`; button → `Button`; stock line → small `Badge`-style; flash countdown → keep logic, restyle to match.
- **Login.tsx** — already polished; swap internals to `Input`/`Field`/`Button` for consistency (visual output unchanged).
- **Checkout.tsx** — `Field`/`Input`/`Button`; `STATUS_COLORS` → `StatusBadge`; card shells → `Card`; `formatPrice` from `lib`.
- **Orders.tsx** — `STATUS_COLORS` → `StatusBadge` (canonical mapping); `formatPrice` from `lib`; tabs/buttons → shared styling.
- **Coins.tsx**, **Profile.tsx**, **Dashboard.tsx** — already near-dialect; swap to `Card`/`PageHeader`/`Input`/`Button` for structural consistency.
- **Chat.tsx**, **Admin.tsx**, **Register.tsx**, **ForgotPassword.tsx** — same sweep.

**Behavior frozen:** no page's logic, state, data flow, or API calls change — only presentation.

---

## 4. Backend design — behavior-preserving consolidation

Extract the verified duplications into shared helpers. Each is a refactor: same inputs, same outputs, same API contract.

### 4.1 `RedisKeys` constants class (`common/RedisKeys.java`)
Centralizes the 11 key prefixes as `static String` builders, e.g. `orderStatus(long id)`, `orderIdMap(String id)`, `seckill(long pid, long userId)`, `stock(long id)`, `product(long id)`, `productsPage(...)`, etc. All ~45 concatenations switch to `RedisKeys.x(...)`. **Behavior-identical** (same resulting strings).

### 4.2 `Money`/`CoinMath` util (`common/Money.java`)
- `COINS_PER_YUAN = 100` public constant (promoted from `CoinServiceImpl` private).
- `subtotalCents(Map<Long,Product> byId, Map<Long,Integer> qty)` — dedupes `CheckoutServiceImpl:150-154` == `CoinServiceImpl:113-115`.
- `centsToCoins` / `coinsToCents` helpers.
- Referenced by `StreamConsumerService` instead of its implicit equivalence.

### 4.3 `PageResults` util (`common/PageResults.java`)
- `static <T> PageResult<T> of(IPage<T> page)` — collapses the 6× `PageResult.of(total,current,size,records)` tail.
- Move `keywords` split + `sortOrderSql` normalization **onto `PageQuery`** (it already documents these as derived fields) so `ProductServiceImpl.page` stops re-deriving them.

### 4.4 `SecurityUtils.requireUser(...)` / ownership helpers
- Add `UserService.requireOwnedOrder(orderId, userId)` — collapses the 6× guard in `OrderServiceImpl` + `AdminOrderServiceImpl:83`. Behavior-identical (same `NOT_FOUND` exception, same message).
- Add `SecurityUtils.requireUserMatching(UserService, username, userId)` (or a `UserService.requireCurrentUser(userId)` convenience) — collapses the 4× `findByUsername(currentUsername)` + id-match dance (2c). Behavior-identical (same `USER_NOT_FOUND` exception, same message).

### 4.5 Idempotent-insert helper
- A small `IdempotentInsert` utility (or `DataIntegrityRetry` helper) consolidating the 5× `try{insert}catch(DataIntegrityViolationException)` blocks. Recovery bodies differ, so the helper takes a `Runnable onDuplicate` callback. Behavior-identical.

### 4.6 Explicitly FLAGGED for user review (not auto-done)
These touch behavior/API surface or are risky — **log + skip, per autonomy directive**:
- **Raw `RuntimeException` → `BusinessException` fix** (`CheckoutServiceImpl:192`, `StreamConsumerService:388`). Technically changes the error response shape (500-generic → `Result.fail(INTERNAL_ERROR,...)`). Low risk, arguably a bugfix, but it alters observable behavior → flagged.
- **3 controllers' pagination → `PageQuery` unification** (`AdminOperationLogController`, `AdminChatController`, `ChatController`). Changing `@RequestParam page/size` → `@ParameterObject PageQuery` changes the query param names (`page`→`current`) → **API-breaking**. Flagged, not done.
- **`@PreAuthorize` migration for admin endpoints** — changes security enforcement location; flagged.
- **`@Phone`/`@SmsCode` validators + `PhoneCodeRequest` base** — touches DTOs/validation; flagged as a nice-to-have.

The five primary extracts (4.1–4.5) are all behavior-preserving.

---

## 5. Cloud deployment learning — to `172.29.231.145`

After the frontend (and optional backend) polish is built and verified locally, walk through deploying to the mentor's server `172.29.231.145` (root / Bigdata_2023). This is a **learning journey**, done step by step, honoring "已有的东西不要动" + "端口冲突就换一个":

1. **Recon first (read-only):** SSH in, survey what's already running (`ss -tlnp`, `systemctl list-units --type=service`, `ufw status`, existing `/opt` dirs, nginx sites, docker). Do NOT touch anything. This determines which ports/paths are free.
2. **Pick non-conflicting ports:** if 80/8081/3306/6379 are taken, choose alternates (e.g. backend 18081, nginx 8080 or a non-root port). The existing VirtualBox deployment on 8888 is a separate host — irrelevant here.
3. **Hardening check** (against the existing `COMPLETE-DEPLOYMENT-GUIDE.md`): UFW rules, don't expose DB/Redis, fail2ban. Reuse the existing guide's steps where the server is already hardened; only add what's missing.
4. **Transfer + deploy** the improved build (jar + frontend `dist` + configs) following the existing guide's Part 4–6, adapted to the chosen ports and to *not* clobber existing services/data (own directories, own DB name or schema, own systemd unit name).
5. **Verify** end-to-end against the new host:port, confirm existing services still untouched.

Deployment steps that would touch existing data/services (e.g. dropping a DB, overwriting nginx default, binding an in-use port) are NOT done — flagged for the user.

---

## 6. Testing & verification

- **Frontend:** `cd frame-frontend && npm run build` (tsc + vite build must pass) + `npm test` (existing vitest suite must stay green). Visual: spot-check each swept page in `npm run dev`.
- **Backend:** `cd frame-backend && mvn clean package` (compile + existing tests must pass). The 67/67 e2e suite must remain green — behavior-preserving refactors must not break contracts. If a refactor can't be proven safe, it's skipped.
- **Deployment:** `curl` smoke tests (register/login/list products) against the new host:port; admin login; a checkout round-trip.

## 7. Order of work (implementation plan will detail)

1. Frontend: build `lib/format.ts` + the `ui/` kit (with small vitest tests for `formatPrice`/`cn`/`StatusBadge`).
2. Frontend: sweep pages, page by page, building after each.
3. Backend: extract 4.1–4.5 (behavior-preserving), `mvn package` + e2e after each.
4. Log the flagged items (4.6) in `docs/superpowers/skipped-issues.md`.
5. Cloud deployment learning journey to `172.29.231.145`.

## 8. Out of scope

- New visual identity / theme / gradients (user chose consistency-pass).
- Backend behavior/API changes (raw-exception fix, pagination-param rename, @PreAuthorize migration) — flagged, not done.
- 99.95% availability / monitoring / OAuth2 / 10k-QPS proof (already NFR-deferred per project memory).
