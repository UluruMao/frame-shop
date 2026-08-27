# Feature Round — Design Spec

> **STATUS (2026-08-27):** Brainstormed + approved. Scope = *five net-new features*, built on current single-instance `main` (`bb7a9e0`). The LLM customer-service bot is **dropped** (per user). Distributed-deployment hardening is a **separate spec**, sequenced *after* this feature round (user chose features-first sequencing: features ship on the current single-instance setup, then the distributed workstream lands on top). Predecessor: `2026-08-26-ui-refinement-design.md` (UI kit + refinement round, merged).

## 1. Problem & Goal

The `use case/refine plan draft.txt` lists net-new features that were deliberately deferred from the last "refine existing only" round. They remain undone:

1. **Account self-deletion** — guests cannot delete their account.
2. **Chat deletion synced admin↔user** — guests cannot delete a chat; admin copy is never synced.
3. **Dashboard time-filters + multi-chart analytics** — the Dashboard has one hardcoded 6-month bar chart, no time filters.
4. **Flash-sale percentage field** — flash-sale is a boolean toggle, not a discount %.
5. **Separate admin/guest frontends + login pages** — one shared login + one shared nav today.

The draft also named an **LLM customer-service bot** — **dropped** from scope per user (2026-08-27).

**Goal:** Deliver all five net-new features, consistent with the existing architecture and all-Chinese UI, on the current single-instance deployment — building a defensible, complete platform. Each feature must remain correct under the later distributed hardening (its *contract/behavior* must not need rework; only infra wiring may change).

**Non-goal (deferred to the separate distributed-deployment spec):** orders-queue Stream-consumer-group restore, Redis pub/sub chat broker, Redis admin presence, scheduler leader election, config host externalization, shared uploads decision. Sequenced after this round.

## 2. Locked Decisions

| Decision | Choice |
|---|---|
| Sequencing | Features first (single-instance), then distributed hardening as a separate spec |
| LLM customer-service bot | **Dropped** entirely |
| Account deletion model | **Soft delete** — anonymize PII, retain `user.id` + all transactional rows (orders/reviews/coins) for analytics + audit |
| Account deletion confirm | Two-click confirm in Profile UI → password re-auth → commit |
| Chat deletion model | **Soft delete whole session** — `chat_session.status = DELETED`, hidden from both inboxes, messages retained for audit |
| Chat deletion scope | Whole-session delete (guest-initiated); no per-message delete |
| Dashboard time-filter | Preset ranges (`1d\|7d\|30d\|90d\|365d\|1095d`) + custom `from`/`to` date range |
| Dashboard charts | Three Recharts charts — bar (units/product), line (revenue trend), pie (sales share) — all driven by the shared time-filter |
| Dashboard data source | Charts query `order_item` snapshots (deleted products persist in history); 商品数 tile stays a live `product` count |
| Flash-sale discount | Per-product `discount_percent` integer 1–99; flash price = `price_in_cents * (100 - discount_percent) / 100` |
| Flash-sale confirm | Two-step Modal ("确认秒杀折扣为 N%?"), no re-auth (it's a product-config action) |
| Admin/guest split | **Single React app, role-split routes** — distinct `/admin/login`, distinct admin layout; admin section = separate routes (decomposes the large `Admin.tsx`) |

## 3. Approach & Sequencing

Task-by-task SDD discipline (mirrors prior rounds). Backend footprint varies per feature; tests per existing patterns (JUnit backend, Vitest frontend). E2E is **not required** this round — it runs against the VM which holds the old contracts; per the prior user decision the VM is not being redeployed for this round.

1. **Account self-deletion** — backend `POST /auth/account/delete` (password re-auth) + soft-delete service + `DELETED` status check in JWT filter; frontend Profile card with two-step confirm.
2. **Chat deletion sync** — backend `DELETE /chat/sessions/{id}` (ownership-checked) + admin-inbox query filter; frontend Chat "删除聊天" button.
3. **Dashboard analytics** — backend `range`/`from`/`to` params on `GET /admin/stats` + time-windowed mapper queries; frontend three Recharts charts + filter UI.
4. **Flash-sale percentage** — backend `discount_percent` column + flash-price computation in checkout/stock-gate path; frontend percentage input + two-step Modal.
5. **Admin/guest route split** — frontend-only: `/admin/login` + admin layout + separate routes (`/admin/dashboard`, `/admin/products`, `/admin/orders`, `/admin/chat`); decompose `Admin.tsx`.

Features are independent and may be built in any order; the suggested order front-loads the two deletion features (smallest, self-contained), then the meatier Dashboard + flash-sale, then the route-split refactor (which touches the most files).

## 4. Component & Unit Designs

### 4.1 Account self-deletion

**Backend.**
- New endpoint `ProfileController`: `POST /auth/account/delete` — body `{ password }`. Verifies password against the current `user.password_hash` (reuse the existing `passwordEncoder.matches`); on mismatch → 409 BusinessException with Chinese message "密码不正确".
- New `UserService.deactivateAccount(Long userId)`: within one `@Transactional`:
  - `user`: set `username = '[已注销]' || id` (uniqueness-safe), `phone = NULL`, `email = NULL`, `password_hash = NULL`, `avatar_url = NULL`, `nickname = NULL`, `signature = NULL`, `status = DELETED`.
  - `chat_session`: set `status = CLOSED` for all open sessions of this user (`UPDATE chat_session SET status='CLOSED' WHERE user_id=? AND status='OPEN'`).
  - Delete avatar files on disk matching `./uploads/avatars/avatar_{userId}_*`.
  - **Retain untouched:** `orders`, `order_item`, `review`, `coin_ledger` rows (analytics + audit). The coin balance dies with login (un-loginable).
- JWT revocation: add a `DELETED` status check in `JwtAuthenticationFilter` (or `UserDetailsService`) → if the resolved user has `status != ACTIVE`, return 401. This makes existing tokens invalid immediately without a denylist. (A `status` field already exists per Phase A.)
- `UserStatus` enum gains a `DELETED` value (if not present).

**Frontend.** `Profile.tsx` — a "注销账号" card (danger zone) at the bottom:
- Step 1: button "注销账号" → opens a Modal "确认注销? 此操作不可逆，您的订单和消费记录将保留。" with [取消] / [继续].
- Step 2 (on 继续): a password input + [确认注销]. Calls `POST /auth/account/delete { password }`.
- On success: `clear()` auth store, `navigate('/login')`, toast "账号已注销".

**Tests.** Backend: `deactivateAccount` anonymizes PII, closes chat sessions, leaves orders intact, 401s subsequent JWT; wrong-password → 409. Frontend: Profile card flow (mock the API).

### 4.2 Chat deletion sync

**Backend.**
- New endpoint `ChatController`: `DELETE /chat/sessions/{id}` → `deleteSession(id, currentUserId())`. Ownership-checked via the existing `loadAndCheckOwnership` pattern (IDOR-safe): if `session.userId != currentUser` → 403.
- `ChatService.deleteSession`: `UPDATE chat_session SET status='DELETED' WHERE id=? AND user_id=?` (soft delete). Messages retained.
- `openSessions()` (admin inbox query) and the user's own session list: add `AND status NOT IN ('DELETED','CLOSED')` filter so deleted sessions vanish from both inboxes. (CLOSED sessions already filtered for admin inbox; align the user side.)
- No hard delete, no per-message delete.

**Frontend.** `Chat.tsx` — a "删除聊天" button on the session header (or a trash icon). On click → `confirm()` (or Modal) "删除此聊天? 管理员端也将不再显示。" → `DELETE /chat/sessions/{id}` → clear local state + navigate to `/products` (or show empty state). Admin `ChatReplyPanel`/inbox already filters via the backend query, so no admin-side change beyond the query filter.

**Tests.** Backend: owner deletes → session `DELETED` + invisible to both inboxes; non-owner → 403; messages still in DB.

### 4.3 Dashboard analytics

**Backend.**
- `AdminStatsController` `GET /admin/stats` gains query params `range` (one of `1d,7d,30d,90d,365d,1095d,custom`) and optional `from`/`to` (ISO LocalDate, required when `range=custom`). Default `range=30d`.
- Time window computed server-side: `to = now`, `from = to - duration(range)` (or the explicit `from`/`to`). All metric queries get `WHERE {ts} >= ? AND {ts} < ?`. Timestamp column choice (resolved, per metric):
  - **Revenue** (`totalRevenueCents`, revenue-trend chart): `orders.paid_at` — only paid orders count as revenue; `paid_at` is when the money landed.
  - **Total orders** (`totalOrders` tile): `orders.create_time` — counts orders placed in the window regardless of status.
  - **Refund rate** (`refundCount`): `orders.paid_at` as the window base (refunds relative to orders paid in the window) — simplest consistent choice; document in code.
  - **Units per product / sales share (charts):** `orders.paid_at` (join `order_item`) — sales of paid orders.
  - **Avg rating** (`avgRating`): `review.create_time` — reviews written in the window.
  - The three chart datasets:
    - **Units per product (bar):** `SELECT oi.product_id, oi.name, SUM(oi.quantity) FROM order_item oi JOIN orders o ON oi.order_id=o.id WHERE o.paid_at >= ? AND o.paid_at < ? AND o.status IN (PAID,SHIPPED,DELIVERED,COMPLETED) GROUP BY oi.product_id, oi.name ORDER BY SUM(oi.quantity) DESC` — uses `order_item` snapshot `name`, so deleted products persist.
    - **Revenue trend (line):** time-bucketed `SUM(o.paid_in_cents)` — bucket size by range (day for ≤90d, week for ≤365d, month otherwise). `GROUP BY` a date-trunc expression.
    - **Sales share (pie):** `SUM(oi.quantity)` (or `SUM(oi.price*qty)`) per product — same query as bar, just rendered as share.
- `DashboardStatsVO` extends with `unitsByProduct`, `revenueTrend` (list of `{bucket, cents}`), `salesShareByProduct`. The 5 stat tiles (total revenue, total orders, products, refund rate, avg rating) are re-windowed too.

**Frontend.** `Dashboard.tsx`:
- Filter bar: a `Select` for preset range + two `date` inputs (enabled on `custom`) + apply button.
- Three Recharts charts (already a dep): `<BarChart data={unitsByProduct}>`, `<LineChart data={revenueTrend}>`, `<PieChart data={salesShareByProduct}>`.
- Stat tiles unchanged in shape, re-fetched on filter change.
- `api/stats.ts` `getAdminStats(params)` carries the filter params.

**Tests.** Backend: time-windowed queries return correct subsets; custom range validated; default 30d. Frontend: filter bar drives refetch; charts render mock data.

### 4.4 Flash-sale percentage

**Backend.**
- New column: `ALTER TABLE product ADD COLUMN discount_percent INT DEFAULT NULL` (and the dev `schema.sql` `product` DDL). `Product` entity + `ProductDTO` gain `discountPercent` (Integer, nullable).
- Flash price helper: `Product.flashPriceInCents()` (or a static util) → `isFlashSale ? priceInCents * (100 - discountPercent) / 100 : priceInCents`. Used by checkout total computation, stock-gate (the sale price the customer pays), and the product-detail response.
- Validation: when `isFlashSale=true`, `discountPercent` must be 1–99 (else 400 "折扣比例必须为 1-99"); when `isFlashSale=false`, `discountPercent` is cleared/ignored.
- Checkout: the per-item price used in total = flash price (if the item is flash & sale is live), else list price. Existing coin cap (min(balance, non-flash subtotal)) is unaffected — coins already apply to non-flash items only (mixed-cart rule from the last round).

**Frontend.** Admin product form (`Admin.tsx` or the new `/admin/products` page):
- When the flash checkbox is checked: show a number input "秒杀折扣 (%)" 1–99 + a live preview "秒杀价 ¥X" (computed).
- On submit: two-step Modal — "确认秒杀折扣为 N%? 秒杀价为 ¥X。" [取消] / [确认]. Only after confirm does the create/update call fire.
- Product list/detail: show 原价 (struck-through) + 秒杀价 when flash & live.

**Tests.** Backend: `flashPriceInCents` math; checkout uses flash price for live flash items; invalid `discountPercent` → 400. Frontend: two-step Modal blocks submit until confirmed; preview computes correctly.

### 4.5 Admin/guest route split

**Frontend only.** One React app, role-split route tree.
- New `/admin/login` page — admin-specific login (can reuse the login form, distinct branding/route). On the main `/login`, if the logged-in user is `ADMIN`, redirect to `/admin/login` (or directly `/admin/dashboard`).
- New `AdminLayout` (sibling to `MainLayout`) with admin nav: 控制台 `/admin/dashboard`, 商品 `/admin/products`, 订单 `/admin/orders`, 客服 `/admin/chat`.
- Routes:
  - `/admin/login` (standalone, no shell).
  - `/admin` → `<AdminLayout>` with children: `index` → redirect `/admin/dashboard`; `dashboard`, `products`, `orders`, `chat`.
  - These pages are extracted from the current monolithic `Admin.tsx`:
    - `AdminDashboard` ← existing `Dashboard.tsx` (move under `/admin/dashboard`; the old `/dashboard` route redirects here for admins).
    - `AdminProducts` ← the products tab of `Admin.tsx` (form + table + flash-sale open).
    - `AdminOrders` ← the orders tab of `Admin.tsx`.
    - `AdminChat` ← the chat-inbox tab of `Admin.tsx` (`ChatReplyPanel`).
  - Guest section stays `/*` in `MainLayout`: products, checkout, orders, coins, profile, chat.
- `RequireAuth` + `RequireAdmin` (new, or a role check in `RequireAuth`) gate `/admin/*` — backend already enforces `@PreAuthorize`.
- `IndexRoute`: admin → `/admin/dashboard`; user/guest → `/products` (unchanged for non-admin).
- `MainLayout.navFor`: admin nav removed from the guest layout (admins use `AdminLayout`).

**Tests.** Frontend: routing — admin lands on `/admin/dashboard`; guest cannot reach `/admin/*` (redirects to login/products); `AdminLayout` renders admin nav.

## 5. Cross-Cutting

- **Language:** all new strings Chinese (zh-CN), consistent with the last round. No i18n framework.
- **Security:** `@PreAuthorize("hasRole('ADMIN')")` on new admin endpoints; ownership checks (`loadAndCheckOwnership` pattern) on user-facing mutations (account delete, chat delete). IDOR-safe.
- **No FK constraints exist** in the schema (every relation is by indexed column) — all cascade is app-level inside `@Transactional` (account deletion, chat status updates). Document this explicitly in code comments.
- **Distributed-compat:** none of these features introduce per-JVM state. Account-deletion's JWT-status-check reads the DB (shared). Chat deletion writes the shared DB. Dashboard queries the shared DB. Flash-sale uses the shared Redis stock gate. So all five are multi-instance-safe by construction — the later distributed spec changes only infra wiring, not these contracts.

## 6. Out of Scope

- LLM customer-service bot (dropped).
- Orders-queue Stream consumer-group restore, Redis chat broker, Redis presence, scheduler leader election, config host externalization, shared-uploads decision → separate distributed-deployment spec.
- VM redeploy + e2e regression for this round (per prior decision; VM holds old contracts).
- Cloud deploy execution (recon + topology decided at the distributed-spec stage).

## 7. Risks

- **`Admin.tsx` decomposition** is the highest-churn task (it currently holds products + orders + chat). Do it last, after the flash-sale % work (which also touches the product form), to avoid merge churn within the round.
- **Flash-price math at checkout** must flow through the existing stock-gate + idempotency path without breaking the mixed-cart coin rule — verify the flash price is used consistently in `CheckoutServiceImpl` total + `CoinServiceImpl.previewCheckoutDiscount` cap.
- **Account-deletion JWT revocation** via status check adds a DB read on the auth filter hot path. Acceptable (the filter already resolves the user); confirm it doesn't double-query.
