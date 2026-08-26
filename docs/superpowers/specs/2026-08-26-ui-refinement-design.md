# UI Refinement — Design Spec

> **STATUS (2026-08-26):** Brainstormed + approved. Scope = *refine existing UI only*; no new feature subsystems. Branch `feature/ui-kit-and-consolidation`. Predecessor: `2026-08-25-frontend-beautify-and-reuse-design.md` (kit + backend modular kits done, T1-T24, review-clean).

## 1. Problem & Goal

The Frame Shop frontend works (UC000-UC018 all functional, 73/73 vitest green) but the UI is not yet genuinely clean:

- The UI kit is incomplete — no Modal, Tabs, Table, or Pagination primitives, so every page that needs them hand-rewrites the markup (and ReviewModal has real a11y gaps: no focus-trap, no Escape, no `aria-modal`).
- Language is mixed: auth pages (Login/Register/ForgotPassword) are English; commerce pages are Chinese; Dashboard/Admin mix both.
- The Dashboard is the weakest page — fully static demo data (`$12,480`, `284`, `18`, `7`, a hardcoded Recharts bar chart), and it is the first thing a logged-in user sees.
- The top nav is one hard-coded array rendered for everyone — guests see Checkout/Orders/Chat/Profile (which bounce on click), admins see guest menus they don't need, and `Coins` is missing from the nav entirely.
- The flash-sale countdown on `ProductDetailPage` does not tick or auto-flip to "live" — guests must manually refresh (traced: `fmtCountdown` computed once per render, no interval; stock-poll only starts after sale is already live, so the upcoming→live transition never happens without a re-fetch).

**Goal:** Make the existing UI genuinely clean and honest — finish the kit, unify the language to Chinese (zh-CN), make the Dashboard real (real endpoint + realistic demo seed, not faked constants), fix the role-filtered nav + two small bugs — **without** adding new feature subsystems.

**Non-goal (deferred to the next *feature* round):** LLM customer-service bot, account self-deletion, chat deletion (synced admin↔user), Dashboard time-range filters / multi-chart analytics, separate admin/guest frontends, Aliyun SMS activation, VM redeploy + e2e regression, cloud deploy.

## 2. Locked Decisions

| Decision | Choice |
|---|---|
| Scope ambition | Refine existing only — no new feature subsystems |
| Language | All Chinese (zh-CN); auth pages re-labeled to Chinese |
| Dashboard | Real `/admin/stats` endpoint + richer `schema.sql` demo seed (plausible numbers) + existing single Recharts bar chart wired to real monthly revenue; 5 stat tiles; NO time filters / multi-chart analytics (deferred) |
| Nav + roles | Role-filtered single nav (no separate frontends) + 2 small fixes (countdown auto-refresh, admin product image-URL field) |
| UI kit | Full completion — Modal/Dialog (a11y), Tabs, Table, Pagination, Button `success` variant, Orders key fix |
| Deletion features | Deferred — account-deletion + chat-deletion are net-new features, not polish |
| Image upload | URL string only, no file upload — reuse existing `updateProduct`; remove unused `uploadImage` API fn |
| Countdown bug | Investigate + decide — traced; fix is client-side (see §4) |
| Admin nav | Dashboard + Admin + Products (preview only, admins don't shop) |
| Guest landing | `/` → Products catalog (not redirect to login) |
| Stat tiles | 5-up: 总营收 / 总订单 / 商品数 / 退款率 / 平均评分 |

## 3. Approach & Sequencing (Approach A — frontend-first, backend-light)

Mirrors the branch's established task-by-task SDD discipline. Front-loads visible polish; keeps the backend surgical.

1. **Kit completion** — Modal, Tabs, Table, Pagination primitives + Button `success` variant. Tests for each. Foundation everything else builds on.
2. **Countdown fix** — client-side interval on `ProductDetailPage`. Pure frontend.
3. **Page sweep to kit + Chinese** — sweep all 11 pages + ReviewModal to the new primitives (Tabs in Orders/Admin, Table in Orders/Admin, Pagination in Products, Modal-a11y in ReviewModal) and re-label auth pages to Chinese.
4. **Role-filtered nav** — role-aware `navItems` + guest index route = Products; add Coins to nav.
5. **Admin product image-URL field** — `imageUrl` input on the admin product form via existing `updateProduct`; remove unused `uploadImage` API fn. Zero new backend endpoints.
6. **Dashboard real data** — new `/admin/stats` backend endpoint + richer `schema.sql` demo seed + wire the existing single Recharts bar chart to real monthly revenue.
7. **Polish pass** — Dashboard `$`→`¥`, unify heading sizes/weights, raw Checkout coin-input→`Input` kit, Orders key fix (via Table sweep), `formatPrice` input validation. Visual regression + full test suite green.

**Backend footprint is surgical:** one new controller (`AdminStatsController` + service + mapper queries) + the `schema.sql` seed extension + a `SecurityConfig` matcher for `/admin/stats`. Everything else is frontend.

## 4. Component & Unit Designs

All new kit components follow existing conventions: plain function components, Tailwind utilities inline, `cn()` from `@/lib/format`, `forwardRef` where a ref makes sense, located in `src/components/ui/`. One component per file, each with a vitest test.

### 4.1 `Modal.tsx` (kit)

`Modal({ open, onClose, title, children, footer, size? })`. Rendered via a React portal to `document.body`. Compound: `Modal.Header` / `Modal.Body` / `Modal.Footer`.

**A11y (the whole point — closes the ReviewModal gap):**
- `role="dialog"` `aria-modal="true"`, `aria-labelledby` pointing at the title id.
- Focus trap: on open, focus the first focusable element inside; on close, restore focus to the element that had focus before opening (the trigger).
- `Escape` key closes.
- Click on the backdrop closes.
- Body scroll locked while open.

Sizes: `sm` (`max-w-sm`), `md` (`max-w-md`), `lg` (`max-w-lg`). Default `md`.

Consumers: ReviewModal (rewrite to use this), the Orders refund-reason modal, any future confirm dialogs.

### 4.2 `Tabs.tsx` (kit)

`Tabs({ tabs: { key, label, content }[], defaultKey? })`. Controlled-or-uncontrolled (tracks active key internally if `defaultKey` given, or accepts `activeKey`+`onTabChange` for controlled use). Pill-style active state matching the existing nav pattern (`bg-brand-50 text-brand-700` active, `text-slate-600 hover:bg-slate-100` inactive).

Consumers: Orders tab bar (全部/未付款/未发货/未签收/未评价/已完成) and Admin tab bar (商品/订单/客服). One primitive dedupes both.

### 4.3 `Table.tsx` (kit)

`Table({ columns: { key, header, render? }[], data, rowKey, empty? })`. Striped `bg-slate-50` rows, `text-sm`, `border-slate-200` — matching the existing hand-written tables. Column `render` functions keep it flexible for StatusBadge/action-button cells. `rowKey` drives React keys (this is what kills the Orders `rows.map` key warning).

Consumers: Orders (line items + orders tables), Admin (products + refund requests + orders tables).

### 4.4 `Pagination.tsx` (kit)

`Pagination({ current, size, total, onChange })`. Shows page numbers (computed from `total/size`), prev/next buttons, disabled-at-bounds. Chinese labels (上一页 / 下一页 / 第 N 页 / 共 N 页). Replaces the raw `border px-3 py-1 rounded` buttons in `Products.tsx` (lines 95-100) — closes the ledger's "pagination buttons left raw" deferred minor.

### 4.5 Button `success` variant (kit)

Add `success` to the existing `VARIANT` map in `Button.tsx`: `bg-green-600 hover:bg-green-700 text-white`, same shape as `primary`. Restores the green "go" affordance on the Checkout Pay button (the ledger's T7 deferred minor — kit had no `success`, so Pay went green→brand-blue).

### 4.6 Orders.tsx `rows.map` key fix

Resolved structurally by the Table sweep (§4.3) — when Orders adopts `Table` with `rowKey`, the fragment-as-map-root-with-key-on-inner-`<tr>` pattern is eliminated and the React key-warning vanishes. No separate code change.

### 4.7 Countdown fix (`ProductDetailPage.tsx`)

**Bug (traced in `ProductDetailPage.tsx` lines 49-59, 64-67):**
- `fmtCountdown(flashStart - Date.now())` (line 67) is computed once per render. There is no interval ticking it, so the displayed `倒计时 hh:mm:ss` is frozen at the last render's value.
- The 4s live-stock poll (lines 49-59) returns early at line 51 when the sale is *upcoming* (`flashStart > Date.now()`). It only starts after the sale is already live.
- When the countdown reaches zero, nothing re-fetches `detail`, so `isFlashUpcoming` never flips to `isFlashLive` and the stock-poll effect never re-evaluates. The guest must manually refresh — exactly the reported bug.

**Fix (pure client-side):** a 1s `setInterval` that:
1. Ticks a `now` state so the countdown display re-renders each second.
2. When `flashStart - now <= 0` and the sale was previously upcoming, re-fetches `getProductDetail(id)` → `detail` updates → the existing live-stock poll effect re-evaluates its guard and starts polling live stock automatically.

No backend change. The guest sees a live-ticking countdown and the page transitions to live-stock polling on its own when the sale starts.

### 4.8 Role-filtered nav (`MainLayout.tsx` + `router/index.tsx`)

**`MainLayout.tsx`:** replace the single hard-coded `navItems` array (rendered for everyone) with role-aware filtering:
- **Guest** (`user === null`): `商品 (Products)` only.
- **Logged-in non-admin** (`role === 'USER'`): `商品 / 购物车 / 结算 / 订单 / 金币 / 客服 / 个人中心`.
- **Admin** (`role === 'ADMIN'`): `控制台 (Dashboard) / 商品 (Products, preview) / 管理后台 (Admin)`. Admins don't shop (no Checkout/Orders/Coins/Chat/Profile in their nav).

`Coins` is added to the nav (currently missing). All labels Chinese. The `useIsAdmin()` hook + `user.role` already exist in `useAuthStore`; the guest signal is `user === null`. No new auth plumbing.

**Cart entry:** the cart is currently client-side via `CartContext` (no cart route). During implementation I'll inspect the cart surface and wire the 购物车 entry sensibly — most likely a cart affordance that opens the checkout flow, matching the existing client-side cart model. If no clean cart surface exists, the entry is omitted rather than linked to a non-existent route.

**`router/index.tsx` index route:** currently `/` → Dashboard (RequireAuth-gated, so guests bounce to login). Change to: **guest `/` → Products catalog**; **logged-in `/` → Dashboard**. Implemented via a small index element that branches on auth state (or a redirect) — whichever is cleaner in the react-router data-router API. The public catalog stays browseable per UC000.

### 4.9 Admin product image-URL field (`Admin.tsx`)

Add an `imageUrl` text `Input` to the admin product create/edit form. On save it flows through the **existing** `updateProduct` / `createProduct` (the `Product` type already carries `imageUrl`). Remove the currently-unused `uploadImage` API function (`api/product.ts` lines 41-46) — dead code, since this round is URL-only. Zero new backend endpoints.

### 4.10 Dashboard real data

#### 4.10.1 Backend — `AdminStatsController` + service + mapper

`AdminStatsController` mirrors `AdminOrderController`. `GET /admin/stats` → `Result<DashboardStatsVO>`, gated `@PreAuthorize("hasRole('ADMIN')")` + a `/admin/stats` matcher added to `SecurityConfig` (matches the existing explicit-matcher pattern in `SecurityConfig.java` lines 108-132).

**`DashboardStatsVO`** (one round-trip):
```
totalRevenueCents  = SUM(orders.paid_in_cents) WHERE status IN (PAID, SHIPPED, DELIVERED, COMPLETED)
totalOrders        = COUNT(orders)  (all statuses)
totalProducts      = COUNT(product)
refundCount        = COUNT(orders WHERE refund_status = 'APPROVED')
refundRate         = refundCount / totalOrders   (computed, rounded to 2 decimals; 0 if totalOrders=0)
avgRating          = AVG((quality_stars + logistics_stars + service_stars) / 3.0) across all reviews  (0 if none)
monthlyRevenue[]   = [{ month: "2026-01", revenueCents: SUM(paid_in_cents grouped by month of paid_at) }] for the last 6 months
```

All sourced from columns the backend probe confirmed exist: `orders.paid_in_cents` / `status` / `refund_status` / `paid_at` / `create_time`; `review.quality_stars` / `logistics_stars` / `service_stars`; `product`. `sold` stays computed via the existing `order_item` join (not in the VO — not needed for the tiles). One service + a mapper with a few aggregate queries; reuse the `ProductMapper.xml` (lines 38-51) subquery patterns.

#### 4.10.2 Backend — richer `schema.sql` demo seed

Extend the existing seed (currently 1 admin + 2 products, everything else empty) with a **plausible pre-existing dataset** — not obviously fake, clearly marked as demo:
- ~6 products across categories with varied prices/stock.
- ~4 non-admin users (1 admin already exists) with realistic usernames/phones. Passwords = a known bcrypt hash documented in the seed comment (so a reviewer can log in as a demo user).
- ~120 orders spread across statuses (PAID/SHIPPED/DELIVERED/COMPLETED/REFUNDED/CANCELLED) with `paid_in_cents` + `paid_at` timestamps across the last 6 months, so the monthly-revenue chart has a believable trend (not the flat 400/300/520 of the current demo).
- ~30 reviews with varied 3-5★ ratings so `avgRating` lands ~4.2.
- Matching `order_item` rows for the orders; a handful of `coin_ledger` rows.

Seed stays in `schema.sql` (the existing manual-destructive reseed pattern — re-run drops + recreates). **No Flyway/Liquibase introduced** (YAGNI + matches the branch's "don't add unrelated infra" discipline). README updated: the seed is demo data; re-run `mariadb -u root -p < src/main/resources/schema.sql` to reset.

#### 4.10.3 Frontend — wire `Dashboard.tsx`

- Calls `getAdminStats()` (new `api/stats.ts`), loading state via `Spinner`/`Skeleton`, error state via `EmptyState`.
- 5 stat tiles (5-up grid, `grid-cols-2 md:grid-cols-5`): 总营收 / 总订单 / 商品数 / 退款率 / 平均评分. Revenue via `formatPrice` (so ¥, not $). Refund rate as a percentage. Avg rating to 1 decimal.
- The Recharts `BarChart` re-pointed at `monthlyRevenue` (x = month, y = revenue in ¥). One chart, as scoped.
- The "Static demo data — replace with a real API call" comment deleted — it *is* a real API call now.

Admin-only: the nav hides Dashboard from non-admins (§4.8), the route stays `RequireAuth`, and the endpoint's `@PreAuthorize` is the real gate.

## 5. Language Unification + Polish Pass

### 5.1 Language — all Chinese (zh-CN)

Every user-facing string becomes Chinese. Reuse the exact terms from the use-case spec (`use case/use cases.txt`) for mentor-defensible consistency:
- **Login** → 登入: 用户名 / 密码 / 登入 / 注册账号 / 忘记密码 / 账户已锁定？
- **Register** → 注册: 用户名 / 密码 / 手机号 / 获取验证码 / 验证码 / 已阅读并同意服务条例与隐私条例 / 注册.
- **ForgotPassword** → 忘记密码: 手机号 / 验证码 / 新密码 / 确认修改.
- **Dashboard** → 控制台; tile labels + chart heading in Chinese.
- **MainLayout nav** → all Chinese (§4.8).
- **Admin** → 商品/订单/客服 tabs + action buttons + table headers in Chinese.
- **Kit defaults** — Pagination 上一页/下一页, Modal close `aria-label="关闭"`.

**No i18n framework** (YAGNI; one language, hardcoded strings). If a second language is ever needed, extraction happens then.

### 5.2 Polish pass

- **Dashboard `$`→`¥`** — `formatPrice` everywhere; no hardcoded `$12,480`.
- **Heading scale unified** — currently mixed (`text-2xl font-bold` on Products, `text-xl font-semibold` on PageHeader/Admin). Standardize on the `PageHeader` kit treatment (`text-xl font-semibold text-slate-800`) across all pages.
- **Checkout raw coin-input** — the `<input className="w-24 px-2 py-1 rounded border border-slate-300">` replaced with the `Input` kit.
- **Orders key fix** — resolved via the Table sweep (§4.6).
- **Button `success` variant** — resolved (§4.5).
- **`formatPrice` input validation** (ledger T1 deferred minor) — small guard: clamp NaN/negative to 0; document that float-cents shouldn't occur. Low-effort closure.

## 6. Testing & Verification (non-negotiable)

- **Frontend:** `npm run test` (vitest — new primitive tests + existing 73 stay green), `npm run build` (tsc + Vite), `tsc --noEmit` clean.
- **Backend:** `mvn clean test` (existing 180 + new `AdminStatsService`/`AdminStatsController` tests stay green).
- **No e2e change this round** — e2e runs against the VM (old backend); the known SMS-cascade gap is pre-existing and out of scope. The report will note that real e2e validation of `/admin/stats` defers to the VM redeploy (same as the ledger's T22 note). Honest about the gap.
- **Visual spot-check** — `npm run dev` + backend on 8081, eyeball every page in the unified Chinese + slate/brand dialect (this is where the dev servers come back up).

## 7. Out-of-Scope This Round (deferred to next feature round)

LLM customer-service bot · account self-deletion · chat deletion (synced admin↔user) · Dashboard time-range filters / multi-chart analytics · separate admin/guest frontends · Aliyun SMS activation · VM redeploy + e2e regression · cloud deploy. These are real but they are *features* — this round is *refinement*.

## 8. Key Files

**Frontend (new):** `src/components/ui/Modal.tsx`, `Tabs.tsx`, `Table.tsx`, `Pagination.tsx`; `src/api/stats.ts`.
**Frontend (edit):** `src/components/ui/Button.tsx` (success variant), `src/components/ReviewModal.tsx` (→ Modal), `src/pages/ProductDetailPage.tsx` (countdown), `src/pages/Dashboard.tsx` (real data), `src/layouts/MainLayout.tsx` (role nav), `src/router/index.tsx` (index route), `src/pages/Admin.tsx` (image-URL, Tabs/Table, Chinese), `src/pages/Orders.tsx` (Tabs/Table, key fix), `src/pages/Products.tsx` (Pagination), `src/pages/Login.tsx`/`Register.tsx`/`ForgotPassword.tsx` (Chinese), `src/pages/Checkout.tsx` (coin-input→kit, success button), `src/lib/format.ts` (formatPrice guard).
**Frontend (remove):** `uploadImage` fn in `src/api/product.ts`.
**Backend (new):** `AdminStatsController`, `AdminStatsService`(+Impl), `DashboardStatsVO`, mapper aggregate queries.
**Backend (edit):** `SecurityConfig.java` (`/admin/stats` matcher), `src/main/resources/schema.sql` (demo seed), `README.md` (seed is demo data).
