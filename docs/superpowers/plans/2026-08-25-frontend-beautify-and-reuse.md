# Frontend Beautify + Component Reuse + Backend Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify the Frame Shop frontend on one polished dialect via a reusable `ui/` kit + `lib/` utils, extract backend duplication into self-contained mergeable kits, then deploy the improved app to `172.29.231.145` without disturbing pre-existing services there.

**Architecture:** Phase 1 builds the frontend shared kit (`components/ui/` + `lib/format.ts`) with vitest tests, then sweeps all 11 pages + 2 components to consume it (behavior frozen, only presentation). Phase 2 extracts 5 self-contained backend kits (`RedisKeys`, `Money`, `PageResults`, ownership/idempotency helpers, `PageQuery` cleanup) one-per-commit, then does 4 small API/behavior improvements gated by the 67/67 e2e suite. Phase 3 is a read-only recon → non-conflicting-port deploy → verify learning journey to the cloud server.

**Tech Stack:** React 18 + TypeScript 5.5 + Vite 5 + Tailwind 3.4 + vitest 2 + @testing-library/react (frontend); Spring Boot 3 + MyBatis-Plus + Redis + MariaDB (backend); systemd + nginx (deploy).

## Global Constraints

- **`verbatimModuleSyntax` is ON** (`frame-frontend/tsconfig.json:22`) → every type-only import MUST use `import type { X }`. Plain `import { X }` for types is a compile error.
- **`@/*` alias → `src/*`** (`tsconfig.json:27`, `vite.config.ts:16`) → import kit as `@/components/ui/Button`, utils as `@/lib/format`.
- **`noUnusedLocals` + `noUnusedParameters` are ON** → no leftover unused imports after a sweep (e.g. removing `Loader2` usage requires removing its import).
- **Existing vitest suite (12 files) asserts on text/labels** → the page sweep MUST preserve test-facing copy verbatim: button labels (`Sign in`, `Register`, `发送`, `提交评价`, `Try again`, `Pay `, `Continue shopping`, `View orders`, `Browse products`, `Start shopping`, `Create`, `Update`, `Ship`, `Deliver`, `Approve`, `Deny`, `Open`, `Selected`, `Refresh`, `Cancel edit`), placeholders (`输入消息…`, `回复消息…`, `Verification code`, `Phone (11 digits)`, `Password (≥8, 2 of upper/lower/special)`, `Username (4-25, alphanumeric)`, `Tracking #`), headings (`Dashboard`, `Checkout`, `My Orders`, `我的金币`, `Profile`, `Admin`, `联系客服`, `商品列表`), and the `加载中…`/`暂无数据` text. When in doubt, keep the existing string.
- **Backend kits are self-contained** → each lives in `common/`/`util/`/`validation/`, depends only on Spring/JDK/MyBatis-Plus stdlib, never on services/controllers. One kit per commit so they're cherry-pickable.
- **Cloud server `172.29.231.145` is sacrosanct** → recon is read-only; own ports/paths/DB name/systemd unit; never touch pre-existing services/data.
- **Backend: open to small API/behavior improvements** on this project's source (not just behavior-preserving refactors). DB schema unchanged.
- **Commit co-author trailer:** end every commit message with `Co-Authored-By: Claude <noreply@anthropic.com>`.

### Working directory & tooling (from project memory)
- Frontend: `cd frame-frontend && npm run dev` (port 5173, proxies `/api`→8081 + `/ws-chat`→8081). Build: `npm run build`. Tests: `npm test`.
- Backend: `cd frame-backend`. Build: `mvn clean package -DskipTests`. JDK 21 + Maven paths are env-specific; assume `mvn` is on PATH or the executor sets `JAVA_HOME`.
- The `formatPrice` util currently lives in `frame-frontend/src/pages/Products.tsx:8-10` and is imported by `Checkout.tsx:8`, `Orders.tsx:16`, `Admin.tsx:21`.

---

## File Structure

### Frontend — new files
- `frame-frontend/src/lib/format.ts` — `formatPrice(cents)` + `cn(...classes)`. Pure, no React.
- `frame-frontend/src/components/ui/Button.tsx` — variant/size/loading button.
- `frame-frontend/src/components/ui/Input.tsx` — canonical focus-ring input (+ `<textarea>` via `as`).
- `frame-frontend/src/components/ui/Field.tsx` — label + Input wrapper.
- `frame-frontend/src/components/ui/Select.tsx` — styled select matching Input.
- `frame-frontend/src/components/ui/Card.tsx` — `Card` + compound `Card.Header`/`Card.Body`.
- `frame-frontend/src/components/ui/Badge.tsx` — generic colored pill.
- `frame-frontend/src/components/ui/StatusBadge.tsx` — owns the single 9-status color map; consumes `OrderStatus` type.
- `frame-frontend/src/components/ui/PageHeader.tsx` — icon + title + count.
- `frame-frontend/src/components/ui/EmptyState.tsx` — icon + title + optional action.
- `frame-frontend/src/components/ui/Spinner.tsx` — `Loader2 animate-spin`.
- `frame-frontend/src/components/ui/Skeleton.tsx` — loading placeholder block.
- `frame-frontend/tests/ui/StatusBadge.test.tsx` — tests the canonical status→color map.
- `frame-frontend/tests/ui/format.test.ts` — tests `formatPrice` + `cn`.
- `frame-frontend/tests/ui/Button.test.tsx` — tests variant rendering + loading state.

### Frontend — modified (the sweep)
Each page switches hand-written controls to the kit. `formatPrice` import moves from `./Products` to `@/lib/format` in: `Products.tsx`, `Checkout.tsx`, `Orders.tsx`, `Admin.tsx`, `ProductDetailPage.tsx`. `STATUS_COLORS`/`ORDER_STATUS_COLORS` deleted from `Checkout.tsx`, `Orders.tsx`, `Admin.tsx` → use `<StatusBadge>`. Older-dialect pages (`Products.tsx`, `ProductDetailPage.tsx`, `Chat.tsx`, `ReviewModal.tsx`) get the unification lift (gray→slate, raw selects→`Select`, `border p-2`→`Input`, `bg-blue-500`→`bg-brand-600`).

### Backend — new files
- `frame-backend/src/main/java/com/iflytek/frame/common/RedisKeys.java` — key-prefix builders.
- `frame-backend/src/main/java/com/iflytek/frame/common/Money.java` — `COINS_PER_YUAN` + subtotal + cents↔coins.
- `frame-backend/src/main/java/com/iflytek/frame/common/PageResults.java` — `of(IPage<T>)`.
- `frame-backend/src/main/java/com/iflytek/frame/common/IdempotentInsert.java` — duplicate-key retry helper.
- `frame-backend/src/main/java/com/iflytek/frame/validation/Phone.java` + `PhoneValidator.java` — `@Phone` constraint.
- `frame-backend/src/main/java/com/iflytek/frame/validation/SmsCode.java` + `SmsCodeValidator.java` — `@SmsCode` constraint.

### Backend — modified (the call-site sweeps + behavior improvements)
`OrderServiceImpl`, `AdminOrderServiceImpl`, `CoinServiceImpl`, `CheckoutServiceImpl`, `StreamConsumerService`, `ProductServiceImpl`, `StockGateServiceImpl`, `ChatServiceImpl`, `PhoneRebindServiceImpl`, `ProfileServiceImpl`, `PasswordServiceImpl`, `OrderStockReleaseService`, `CaptchaServiceImpl`, `LoginAttemptServiceImpl`, `LoggingVerificationProvider`, `PageQuery` (move derived-field logic onto it), `CheckoutServiceImpl` (raw-exception fix), `StreamConsumerService` (raw-exception fix), `SecurityConfig` + admin controllers (`@PreAuthorize`), 3 controllers (pagination unification), 6 DTOs (validators).

### Deploy — new files
- `deploy/cloud-145-deploy.md` — the recon + deploy runbook for `172.29.231.145` (created during Phase 3, records what was found + what ports/paths were chosen).

---

## Phase 1 — Frontend shared kit

### Task 1: `lib/format.ts` — `formatPrice` + `cn`

**Files:**
- Create: `frame-frontend/src/lib/format.ts`
- Test: `frame-frontend/tests/ui/format.test.ts`

**Interfaces:**
- Produces: `export function formatPrice(priceInCents: number): string` and `export function cn(...classes: Array<string | false | null | undefined>): string`.

- [ ] **Step 1: Write the failing test**

```ts
// frame-frontend/tests/ui/format.test.ts
import { describe, it, expect } from 'vitest'
import { formatPrice, cn } from '@/lib/format'

describe('formatPrice', () => {
  it('formats integer cents as $dollars.cents', () => {
    expect(formatPrice(1299)).toBe('$12.99')
    expect(formatPrice(0)).toBe('$0.00')
    expect(formatPrice(5)).toBe('$0.05')
    expect(formatPrice(100)).toBe('$1.00')
  })
  it('handles large values', () => {
    expect(formatPrice(123456789)).toBe('$1,234,567.89')
  })
})

describe('cn', () => {
  it('joins truthy string classes with a space', () => {
    expect(cn('a', 'b', 'c')).toBe('a b c')
  })
  it('drops false / null / undefined', () => {
    expect(cn('a', false, null, undefined, 'b')).toBe('a b')
  })
  it('drops empty strings', () => {
    expect(cn('a', '', 'b')).toBe('a b')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-frontend && npx vitest run tests/ui/format.test.ts`
Expected: FAIL — `Failed to resolve import "@/lib/format"` (module not found).

- [ ] **Step 3: Write minimal implementation**

```ts
// frame-frontend/src/lib/format.ts

/**
 * Format integer cents as a dollar string: 1299 → "$12.99".
 * Mirrors what pages used to inline as `¥{(priceInCents / 100).toFixed(2)}`,
 * but keeps the "$" prefix the codebase already used in Products.tsx:81.
 */
export function formatPrice(priceInCents: number): string {
  return `$${(priceInCents / 100).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

/**
 * Join conditional class strings. Falsy (false/null/undefined/'') entries
 * are dropped. A trivial, dependency-free alternative to clsx — no runtime
 * dep added. Used so components can write `cn('base', isActive && 'active')`.
 */
export function cn(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(' ')
}
```

> ⚠ **Behavior note for the executor:** the existing `Products.tsx:8` `formatPrice` returns `$${(priceInCents / 100).toFixed(2)}` — which does NOT add thousands separators. `toLocaleString` adds them (`$1,234,567.89`). This is a deliberate small improvement (consistent-pass ambition allows it). If any existing vitest test asserts a thousands-separated value without commas, it would break — but the suite uses small cent values (e.g. 1299), so `toLocaleString` and `toFixed(2)` produce identical output for those. Confirm the suite still passes in Task 14.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-frontend && npx vitest run tests/ui/format.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add frame-frontend/src/lib/format.ts frame-frontend/tests/ui/format.test.ts
git commit -m "feat(ui): add lib/format — formatPrice + cn class helper

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: `Button` component

**Files:**
- Create: `frame-frontend/src/components/ui/Button.tsx`
- Test: `frame-frontend/tests/ui/Button.test.tsx`

**Interfaces:**
- Produces: `export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement>` with `variant?: 'primary'|'secondary'|'danger'|'ghost'`, `size?: 'sm'|'md'`, `loading?: boolean`, `icon?: React.ReactNode`. Default export `Button`.

- [ ] **Step 1: Write the failing test**

```tsx
// frame-frontend/tests/ui/Button.test.tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Loader2 } from 'lucide-react'
import Button from '@/components/ui/Button'

describe('Button', () => {
  it('renders children', () => {
    render(<Button>Save profile</Button>)
    expect(screen.getByRole('button', { name: 'Save profile' })).toBeInTheDocument()
  })
  it('applies primary variant classes by default', () => {
    render(<Button>Click</Button>)
    expect(screen.getByRole('button')).toHaveClass('bg-brand-600')
  })
  it('applies danger variant classes', () => {
    render(<Button variant="danger">Delete</Button>)
    expect(screen.getByRole('button')).toHaveClass('bg-red-600')
  })
  it('disables and shows spinner when loading', () => {
    render(<Button loading>Loading</Button>)
    const btn = screen.getByRole('button')
    expect(btn).toBeDisabled()
    expect(btn.querySelector('svg')).toBeInTheDocument()
  })
  it('fires onClick', async () => {
    const fn = vi.fn()
    render(<Button onClick={fn}>Go</Button>)
    await userEvent.click(screen.getByRole('button'))
    expect(fn).toHaveBeenCalledTimes(1)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-frontend && npx vitest run tests/ui/Button.test.tsx`
Expected: FAIL — `Failed to resolve import "@/components/ui/Button"`.

- [ ] **Step 3: Write minimal implementation**

```tsx
// frame-frontend/src/components/ui/Button.tsx
import { type ButtonHTMLAttributes, type ReactNode } from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/format'

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost'
export type ButtonSize = 'sm' | 'md'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  loading?: boolean
  icon?: ReactNode
}

const VARIANT: Record<ButtonVariant, string> = {
  primary: 'bg-brand-600 text-white hover:bg-brand-700',
  secondary: 'bg-slate-100 text-slate-700 hover:bg-slate-200',
  danger: 'bg-red-600 text-white hover:bg-red-700',
  ghost: 'text-slate-600 hover:bg-slate-100',
}

const SIZE: Record<ButtonSize, string> = {
  sm: 'px-2.5 py-1 text-xs',
  md: 'px-4 py-2 text-sm',
}

/**
 * The one button for the whole app. Owns the brand accent + hover + rounded-md
 * + disabled styles so pages stop hand-writing `bg-brand-600 px-4 py-2 text-white`.
 * `loading` disables the button and swaps the icon for a spinner.
 */
export default function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  icon,
  className,
  children,
  disabled,
  ...rest
}: ButtonProps) {
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center gap-1.5 rounded-md font-medium transition-colors disabled:opacity-60 disabled:cursor-not-allowed',
        VARIANT[variant],
        SIZE[size],
        className,
      )}
      disabled={disabled || loading}
      {...rest}
    >
      {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : icon}
      {children}
    </button>
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-frontend && npx vitest run tests/ui/Button.test.tsx`
Expected: PASS (5 tests). Note: `vi` is a global (vitest/globals) — if the test errors on `vi is not defined`, add `import { vi } from 'vitest'` to the test's import line.

- [ ] **Step 5: Commit**

```bash
git add frame-frontend/src/components/ui/Button.tsx frame-frontend/tests/ui/Button.test.tsx
git commit -m "feat(ui): add Button component — variants/size/loading

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: `Input`, `Field`, `Select` components

**Files:**
- Create: `frame-frontend/src/components/ui/Input.tsx`
- Create: `frame-frontend/src/components/ui/Field.tsx`
- Create: `frame-frontend/src/components/ui/Select.tsx`

**Interfaces:**
- Produces: `Input` (`InputHTMLAttributes<HTMLInputElement>` + optional `error?: string`, `as?: 'input'|'textarea'`), `Field` (`label`, `id`, children-or `value`/`onChange`), `Select` (`SelectHTMLAttributes<HTMLSelectElement>` + `options: {value:string,label:string}[]` or children).

- [ ] **Step 1: Write minimal implementation (these are thin presentational wrappers; visual verification via build + later page tests covers them)**

```tsx
// frame-frontend/src/components/ui/Input.tsx
import { type InputHTMLAttributes, type TextareaHTMLAttributes, forwardRef } from 'react'
import { cn } from '@/lib/format'

const BASE = 'w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500'

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  error?: string
}

/** Canonical text input — the single focus-ring + border style every page used to rewrite. */
const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ className, error, ...rest }, ref) => (
    <input ref={ref} className={cn(BASE, error && 'border-red-500', className)} {...rest} />
  ),
)
Input.displayName = 'Input'
export default Input

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {}

/** Canonical textarea sharing the Input style. */
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, ...rest }, ref) => (
    <textarea ref={ref} className={cn(BASE, className)} {...rest} />
  ),
)
Textarea.displayName = 'Textarea'
```

```tsx
// frame-frontend/src/components/ui/Field.tsx
import { type ReactNode } from 'react'

export interface FieldProps {
  label: string
  id?: string
  htmlFor?: string
  children: ReactNode
  hint?: string
}

/** Label + control wrapper. Renders <label> + children; pairs with Input/Select. */
export default function Field({ label, id, htmlFor, children, hint }: FieldProps) {
  const target = htmlFor ?? id
  return (
    <div className="space-y-1.5">
      <label className="block text-sm text-slate-600" htmlFor={target}>
        {label}
      </label>
      {children}
      {hint && <p className="text-xs text-slate-500">{hint}</p>}
    </div>
  )
}
```

```tsx
// frame-frontend/src/components/ui/Select.tsx
import { type SelectHTMLAttributes, type OptionHTMLAttributes } from 'react'
import { cn } from '@/lib/format'

const BASE = 'w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500 bg-white'

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {}

/** Styled select matching the Input look — replaces raw <select> in Products/Admin. */
export default function Select({ className, children, ...rest }: SelectProps) {
  return (
    <select className={cn(BASE, className)} {...rest}>
      {children}
    </select>
  )
}
```

- [ ] **Step 2: Verify it type-checks + builds**

Run: `cd frame-frontend && npx tsc -b --noEmit`
Expected: no errors. (`forwardRef` ref types are strict under `noUnusedParameters` — confirm none unused.)

- [ ] **Step 3: Commit**

```bash
git add frame-frontend/src/components/ui/Input.tsx frame-frontend/src/components/ui/Field.tsx frame-frontend/src/components/ui/Select.tsx
git commit -m "feat(ui): add Input, Textarea, Field, Select components

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: `Card`, `Badge`, `StatusBadge` components

**Files:**
- Create: `frame-frontend/src/components/ui/Card.tsx`
- Create: `frame-frontend/src/components/ui/Badge.tsx`
- Create: `frame-frontend/src/components/ui/StatusBadge.tsx`
- Test: `frame-frontend/tests/ui/StatusBadge.test.tsx`

**Interfaces:**
- Produces: `Card` with `Card.Header`/`Card.Body` compound components; `Badge` (`color?: 'amber'|'green'|'red'|'slate'|'indigo'|'blue'|'orange'`); `StatusBadge` (`status: OrderStatus`).

- [ ] **Step 1: Write the failing test (StatusBadge — the one with real logic: the canonical color map)**

```tsx
// frame-frontend/tests/ui/StatusBadge.test.tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import StatusBadge from '@/components/ui/StatusBadge'
import type { OrderStatus } from '@/api/types'

const CASES: Array<[OrderStatus, string]> = [
  ['PENDING', 'bg-amber-100'],
  ['PAID', 'bg-blue-100'],
  ['SHIPPED', 'bg-indigo-100'],
  ['DELIVERED', 'bg-green-100'],
  ['COMPLETED', 'bg-slate-100'],
  ['REFUND_PENDING', 'bg-orange-100'],
  ['REFUNDED', 'bg-red-100'],
  ['CANCELLED', 'bg-slate-100'],
  ['FAILED', 'bg-red-100'],
]

describe('StatusBadge', () => {
  it.each(CASES)('renders %s with canonical color class %s', (status, cls) => {
    render(<StatusBadge status={status} />)
    expect(screen.getByText(status)).toHaveClass(cls)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-frontend && npx vitest run tests/ui/StatusBadge.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Write minimal implementation**

```tsx
// frame-frontend/src/components/ui/Card.tsx
import { type ReactNode } from 'react'
import { cn } from '@/lib/format'

export interface CardProps {
  children: ReactNode
  className?: string
}

/** Canonical white card shell — replaces ~15 hand-written `bg-white border border-slate-200 rounded-lg p-4`. */
export default function Card({ children, className }: CardProps) {
  return (
    <div className={cn('bg-white border border-slate-200 rounded-lg', className)}>
      {children}
    </div>
  )
}

function CardHeader({ children, className }: CardProps) {
  return <div className={cn('p-4 border-b border-slate-100', className)}>{children}</div>
}
function CardBody({ children, className }: CardProps) {
  return <div className={cn('p-4', className)}>{children}</div>
}

// Compound components — Card.Header / Card.Body.
Card.Header = CardHeader
Card.Body = CardBody
```

```tsx
// frame-frontend/src/components/ui/Badge.tsx
import { type ReactNode } from 'react'
import { cn } from '@/lib/format'

export type BadgeColor = 'amber' | 'green' | 'red' | 'slate' | 'indigo' | 'blue' | 'orange'

const COLOR: Record<BadgeColor, string> = {
  amber: 'bg-amber-100 text-amber-700',
  green: 'bg-green-100 text-green-700',
  red: 'bg-red-100 text-red-700',
  slate: 'bg-slate-100 text-slate-600',
  indigo: 'bg-indigo-100 text-indigo-700',
  blue: 'bg-blue-100 text-blue-700',
  orange: 'bg-orange-100 text-orange-700',
}

export interface BadgeProps {
  color?: BadgeColor
  children: ReactNode
  className?: string
}

/** Generic colored pill. Use for ad-hoc badges; use StatusBadge for order statuses. */
export default function Badge({ color = 'slate', children, className }: BadgeProps) {
  return (
    <span className={cn('px-2 py-0.5 rounded text-xs font-medium', COLOR[color], className)}>
      {children}
    </span>
  )
}
```

```tsx
// frame-frontend/src/components/ui/StatusBadge.tsx
import type { OrderStatus } from '@/api/types'
import { cn } from '@/lib/format'

/**
 * The SINGLE source of the 9-status → color map. Replaces the three duplicated
 * STATUS_COLORS / ORDER_STATUS_COLORS maps in Checkout.tsx, Orders.tsx, Admin.tsx.
 * Canonical mapping (the Admin.tsx set — PAID=blue, SHIPPED=indigo, DELIVERED=green).
 */
const STATUS_COLOR: Record<OrderStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-700',
  PAID: 'bg-blue-100 text-blue-700',
  SHIPPED: 'bg-indigo-100 text-indigo-700',
  DELIVERED: 'bg-green-100 text-green-700',
  COMPLETED: 'bg-slate-100 text-slate-600',
  REFUND_PENDING: 'bg-orange-100 text-orange-700',
  REFUNDED: 'bg-red-100 text-red-700',
  CANCELLED: 'bg-slate-100 text-slate-600',
  FAILED: 'bg-red-100 text-red-700',
}

export interface StatusBadgeProps {
  status: OrderStatus
  /** Optional override label (defaults to the raw status string). */
  label?: string
  className?: string
}

export default function StatusBadge({ status, label, className }: StatusBadgeProps) {
  return (
    <span className={cn('px-2 py-0.5 rounded text-xs font-medium', STATUS_COLOR[status], className)}>
      {label ?? status}
    </span>
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-frontend && npx vitest run tests/ui/StatusBadge.test.tsx`
Expected: PASS (9 cases).

- [ ] **Step 5: Commit**

```bash
git add frame-frontend/src/components/ui/Card.tsx frame-frontend/src/components/ui/Badge.tsx frame-frontend/src/components/ui/StatusBadge.tsx frame-frontend/tests/ui/StatusBadge.test.tsx
git commit -m "feat(ui): add Card, Badge, StatusBadge (single status-color map)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: `PageHeader`, `EmptyState`, `Spinner`, `Skeleton` components

**Files:**
- Create: `frame-frontend/src/components/ui/PageHeader.tsx`
- Create: `frame-frontend/src/components/ui/EmptyState.tsx`
- Create: `frame-frontend/src/components/ui/Spinner.tsx`
- Create: `frame-frontend/src/components/ui/Skeleton.tsx`

**Interfaces:**
- Produces: `PageHeader({icon?, title, count?, className?})`; `EmptyState({icon?, title, action?})`; `Spinner({className?})`; `Skeleton({className?})`.

- [ ] **Step 1: Write minimal implementation**

```tsx
// frame-frontend/src/components/ui/PageHeader.tsx
import { type ReactNode } from 'react'
import { cn } from '@/lib/format'

export interface PageHeaderProps {
  title: ReactNode
  icon?: ReactNode
  count?: ReactNode
  className?: string
}

/** Icon + title + optional count — the header pattern repeated in Checkout/Orders/Coins/Profile/Admin. */
export default function PageHeader({ title, icon, count, className }: PageHeaderProps) {
  return (
    <div className={cn('flex items-center gap-2', className)}>
      {icon}
      <h1 className="text-xl font-semibold text-slate-800">{title}</h1>
      {count != null && <span className="text-sm text-slate-400">({count})</span>}
    </div>
  )
}
```

```tsx
// frame-frontend/src/components/ui/EmptyState.tsx
import { type ReactNode } from 'react'

export interface EmptyStateProps {
  title: string
  icon?: ReactNode
  action?: ReactNode
}

/** Icon + message + optional CTA — replaces bare `暂无数据` text. */
export default function EmptyState({ title, icon, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center gap-2 py-10 text-center">
      {icon && <div className="text-slate-300">{icon}</div>}
      <p className="text-sm text-slate-500">{title}</p>
      {action}
    </div>
  )
}
```

```tsx
// frame-frontend/src/components/ui/Spinner.tsx
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/format'

export interface SpinnerProps {
  className?: string
}

/** Loading spinner — replaces `加载中…` text where a spinner reads better. */
export default function Spinner({ className }: SpinnerProps) {
  return <Loader2 className={cn('w-5 h-5 text-brand-600 animate-spin', className)} />
}
```

```tsx
// frame-frontend/src/components/ui/Skeleton.tsx
import { cn } from '@/lib/format'

export interface SkeletonProps {
  className?: string
}

/** Animated loading placeholder block. */
export default function Skeleton({ className }: SkeletonProps) {
  return <div className={cn('animate-pulse rounded bg-slate-200', className)} />
}
```

- [ ] **Step 2: Verify it type-checks**

Run: `cd frame-frontend && npx tsc -b --noEmit`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frame-frontend/src/components/ui/PageHeader.tsx frame-frontend/src/components/ui/EmptyState.tsx frame-frontend/src/components/ui/Spinner.tsx frame-frontend/src/components/ui/Skeleton.tsx
git commit -m "feat(ui): add PageHeader, EmptyState, Spinner, Skeleton

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Phase 2 — Frontend page sweep

> **Sweep rules (apply to every page task):**
> 1. Replace hand-written `<button class="bg-brand-600…">` with `<Button>`.
> 2. Replace the input class string with `<Input>` / `<Field>`; raw `<select>` with `<Select>`.
> 3. Replace `STATUS_COLORS`/`ORDER_STATUS_COLORS` map + `<span>` with `<StatusBadge status={...} />`.
> 4. Replace `formatPrice` import `from './Products'` with `from '@/lib/format'`; replace inline `¥{(x/100).toFixed(2)}` with `formatPrice(x)`.
> 5. Replace card shells `bg-white border border-slate-200 rounded-lg p-4` with `<Card className="p-4">` (or `<Card><Card.Body className="p-4">`).
> 6. Older-dialect normalization: `text-gray-*` → `text-slate-*`; `bg-blue-500` → `bg-brand-600`; `border p-2` → kit inputs.
> 7. **Preserve every test-facing string** (see Global Constraints). After each page, run that page's existing vitest test.
> 8. Remove now-unused imports (else `noUnusedLocals` fails the build).

### Task 6: Sweep `Products.tsx` (biggest older-dialect lift)

**Files:**
- Modify: `frame-frontend/src/pages/Products.tsx`

**Interfaces:**
- Consumes: `Button`, `Input`, `Select`, `Card`, `EmptyState`, `Spinner`, `formatPrice`, `cn` from Tasks 1–5.
- Produces: `Products` still default-exports; `formatPrice` is now imported FROM `@/lib/format` (no longer defined here — Tasks 7/9/11 update their imports to point at `@/lib/format` instead of `./Products`).

- [ ] **Step 1: Rewrite the component**

Apply: delete the local `formatPrice` (lines 7-10) — it now lives in `@/lib/format`. Search bar: `<input class="border p-2 rounded">` → `<Input>`; raw `<select>` (×2) → `<Select>`; search button → `<Button size="sm" icon={<Search className="h-4 w-4" />}>搜索</Button>`. Product cards → `<Card className="p-3 hover:shadow transition-shadow">` with image `aspect-square`/`object-cover`. Inline `¥{(p.priceInCents/100).toFixed(2)}` → `formatPrice(p.priceInCents)` (keep the `¥`? — the page shows `¥` not `$`; **decision: keep `¥` prefix here, so use `¥${formatPrice(...).slice(1)}` OR add the `¥` to the JSX** — see Step 2 note). Loading `加载中...` → keep text but wrap with `<Spinner />` above it (preserve `加载中` substring for tests: actually the test file `Products.test.tsx` must be checked — see Step 2). Empty `暂无数据` → `<EmptyState title="暂无数据" />`.

- [ ] **Step 2: Check what `tests/Products.test.tsx` asserts, then preserve it**

Run: `cd frame-frontend && cat tests/Products.test.tsx`
Read the assertions. Preserve every queried string/role. **The `¥` vs `$` question:** the existing `formatPrice` returned `$...` but `Products.tsx:81` rendered `¥{...}` (literal `¥` + the `$`-prefixed string would be `¥$12.99` — a latent bug). **Decision:** render `¥{(p.priceInCents / 100).toFixed(2)}` is what's there; to keep it clean, add a `formatPriceCny` or just inline the `¥` + a numeric format. Simplest: keep using `¥` + `toFixed(2)` inline for CNY display in Products/ProductDetailPage/Coins, and use `formatPrice` (the `$` version) only in Checkout/Orders/Admin which already used `formatPrice`. **Verify the actual current render** (`Products.tsx:81` shows `¥{(p.priceInCents / 100).toFixed(2)}` — no `formatPrice` call there). So: leave the `¥` inline as-is; do NOT route Products' price through `formatPrice`. Only move the `formatPrice` *definition* out of Products.tsx.

Revised Step 1 amendment: **Move the `formatPrice` function definition** from `Products.tsx:7-10` to `@/lib/format` (already done in Task 1). In `Products.tsx`, DELETE the local definition. Products.tsx itself does not call `formatPrice` (it inlines `¥`), so no import needed here. Pages that imported `formatPrice from './Products'` (Checkout, Orders, Admin) get repointed in their own tasks.

- [ ] **Step 3: Run the page's existing test**

Run: `cd frame-frontend && npx vitest run tests/Products.test.tsx`
Expected: PASS. If it fails on a changed class/role, fix the page (not the test) to preserve the asserted contract.

- [ ] **Step 4: Build**

Run: `cd frame-frontend && npm run build`
Expected: success (tsc + vite).

- [ ] **Step 5: Commit**

```bash
git add frame-frontend/src/pages/Products.tsx
git commit -m "refactor(ui): sweep Products.tsx to ui kit; move formatPrice to lib

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: Sweep `Checkout.tsx`

**Files:**
- Modify: `frame-frontend/src/pages/Checkout.tsx`

**Interfaces:**
- Consumes: `Button`, `Input`, `Field`, `Card`, `StatusBadge`, `PageHeader`, `Spinner`, `formatPrice` (from `@/lib/format`).

- [ ] **Step 1: Rewrite presentation**
  - `import { formatPrice } from './Products'` (line 8) → `import { formatPrice } from '@/lib/format'`.
  - Delete `STATUS_COLORS` map (lines 11-21); delete the local `StatusBadge` function (lines 472-476) — use the shared `<StatusBadge status={order.status} />`. Note: Checkout's `PAID` was `green`; the canonical map makes `PAID=blue`. The test `Checkout.test.tsx` asserts behavior, not color — verify in Step 3.
  - `<input class="w-full px-3 py-2 rounded-md border border-slate-300...">` → `<Input>`; the local `Field` component (lines 440-470) → use shared `Field`+`Input`.
  - Card shells → `<Card className="p-4">`.
  - Buttons (`Place order`, `Pay …`, `Try again`, `View orders`, `Continue shopping`) → `<Button variant="...">`. Preserve exact labels (`Pay ` + the formatted amount — the test may match `/Pay/`).
  - `PageHeader` local function (431-438) → shared `<PageHeader icon={<ShoppingCart className="w-5 h-5 text-brand-600" />} title="Checkout" />`.
  - `Loader2 animate-spin` → optional `<Spinner>`.

- [ ] **Step 2: Run the page's existing test**

Run: `cd frame-frontend && npx vitest run tests/Checkout.test.tsx`
Expected: PASS. This is a 3-stage flow test — if it breaks, the likely cause is a label or role change; restore the asserted copy.

- [ ] **Step 3: Build + commit**

Run: `cd frame-frontend && npm run build`

```bash
git add frame-frontend/src/pages/Checkout.tsx
git commit -m "refactor(ui): sweep Checkout.tsx to ui kit + shared StatusBadge

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: Sweep `Orders.tsx`

**Files:**
- Modify: `frame-frontend/src/pages/Orders.tsx`

- [ ] **Step 1: Rewrite presentation**
  - `import { formatPrice } from './Products'` (line 16) → `from '@/lib/format'`.
  - Delete `STATUS_COLORS` (18-28); the inline `<span>` status badge (273-278) and the special `退款处理中` case (268-272) → `<StatusBadge status={o.status} label="退款处理中" />` when `REFUND_PENDING` (the canonical color is already orange for REFUND_PENDING, matching). Preserve label `退款处理中`.
  - Tab buttons + table headers unchanged in copy. Action buttons (`详情`, `付款`, `取消`, `退款`, `确认收货`, `申请退款`, `Start shopping`) → keep as text-link buttons (`<Button variant="ghost" size="sm">`) but preserve exact labels.
  - Refund modal: local `<textarea class="border...">` → `<Textarea>`; modal buttons → `<Button>`.

- [ ] **Step 2: Run the page's existing test**

Run: `cd frame-frontend && npx vitest run tests/Orders.test.tsx`
Expected: PASS.

- [ ] **Step 3: Build + commit**

Run: `cd frame-frontend && npm run build`

```bash
git add frame-frontend/src/pages/Orders.tsx
git commit -m "refactor(ui): sweep Orders.tsx to ui kit + shared StatusBadge

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: Sweep `ProductDetailPage.tsx`

**Files:**
- Modify: `frame-frontend/src/pages/ProductDetailPage.tsx`

- [ ] **Step 1: Rewrite presentation**
  - Inline `¥{(detail.priceInCents / 100).toFixed(2)}` → keep `¥` prefix (CNY), leave inline `toFixed(2)` (consistent with Products). `text-gray-*` → `text-slate-*`.
  - Button `加入购物车` (line 81) → `<Button>` (keep label).
  - Loading `加载中...` (line 57) → preserve string; optional `<Spinner>`. Empty `商品不存在` (56) → `<EmptyState title="商品不存在" />` (preserve string).
  - `库存` stock line → small `<Badge>`. Flash countdown text → keep logic, restyle container.

- [ ] **Step 2: Run the page's existing test**

Run: `cd frame-frontend && npx vitest run tests/ProductDetailPage.test.tsx`
Expected: PASS.

- [ ] **Step 3: Build + commit**

Run: `cd frame-frontend && npm run build`

```bash
git add frame-frontend/src/pages/ProductDetailPage.tsx
git commit -m "refactor(ui): sweep ProductDetailPage.tsx to ui kit

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 10: Sweep `Chat.tsx` (older dialect: gray + blue-500)

**Files:**
- Modify: `frame-frontend/src/pages/Chat.tsx`

- [ ] **Step 1: Rewrite presentation**
  - `text-gray-500` → `text-slate-500`; `bg-gray-50` → `bg-slate-50`; `bg-gray-200` → `bg-slate-200`; `bg-blue-500` (user bubble) → `bg-brand-600`; input `flex-1 rounded border px-3 py-2` → `<Input className="flex-1">`; send button `bg-blue-500` → `<Button>` (preserve label `发送`, placeholder `输入消息…`, heading `联系客服`, status text `已连接`/`连接中…`).

- [ ] **Step 2: Run the page's existing test**

Run: `cd frame-frontend && npx vitest run tests/Chat.test.tsx`
Expected: PASS (asserts `联系客服`, `输入消息…`, `发送`).

- [ ] **Step 3: Build + commit**

Run: `cd frame-frontend && npm run build`

```bash
git add frame-frontend/src/pages/Chat.tsx
git commit -m "refactor(ui): sweep Chat.tsx — gray→slate, blue-500→brand-600, kit inputs

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 11: Sweep `Admin.tsx`

**Files:**
- Modify: `frame-frontend/src/pages/Admin.tsx`

- [ ] **Step 1: Rewrite presentation**
  - `import { formatPrice } from './Products'` (line 21) → `from '@/lib/format'`.
  - Delete `ORDER_STATUS_COLORS` (25-35); the `<span class="...ORDER_STATUS_COLORS...">` (700-704) → `<StatusBadge status={o.status} />`.
  - Product form inputs (lines 416-446) → `<Input>`; textarea (448-454) → `<Textarea>`; status-filter `<select>` (655-667) → `<Select>`.
  - All action buttons (`Create`, `Update`, `Cancel edit`, `Ship`, `Deliver`, `Approve`, `Deny`, `Open`, `Selected`, `Refresh`) → `<Button variant=... size="sm">` preserving labels. The `Yes`/`No` flash badges → `<Badge color="amber">Yes</Badge>` / `<Badge>No</Badge>`.
  - Tracking input → `<Input size="sm">`.

- [ ] **Step 2: Build + commit** (no dedicated Admin.test.tsx exists; rely on build + the full suite)

Run: `cd frame-frontend && npm run build`

```bash
git add frame-frontend/src/pages/Admin.tsx
git commit -m "refactor(ui): sweep Admin.tsx to ui kit + shared StatusBadge

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 12: Sweep `Login.tsx`, `Register.tsx`, `ForgotPassword.tsx`

**Files:**
- Modify: `frame-frontend/src/pages/Login.tsx`, `Register.tsx`, `ForgotPassword.tsx`

- [ ] **Step 1: Swap internals to the kit (visual output unchanged)**
  - All three: replace input class string → `<Input>`; submit buttons → `<Button loading={loading}>`; "Get code" buttons → `<Button variant="secondary" size="sm">`. Preserve placeholders (`Username or phone`, `Password`, `Phone (11 digits)`, `Verification code`, `New password (≥8)`, `Username (4-25, alphanumeric)`, `Password (≥8, 2 of upper/lower/special)`) and labels (`Sign in`, `Register`, `Reset password`, `Get code`, `Creating…`, `Resetting…`, `Signing in…`).

- [ ] **Step 2: Run the page tests**

Run: `cd frame-frontend && npx vitest run tests/Register.test.tsx`
Expected: PASS. (Login/ForgotPassword have no dedicated tests; rely on build.)

- [ ] **Step 3: Build + commit**

Run: `cd frame-frontend && npm run build`

```bash
git add frame-frontend/src/pages/Login.tsx frame-frontend/src/pages/Register.tsx frame-frontend/src/pages/ForgotPassword.tsx
git commit -m "refactor(ui): sweep Login/Register/ForgotPassword to ui kit

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 13: Sweep `Dashboard.tsx`, `Coins.tsx`, `Profile.tsx` + components `ReviewModal.tsx`, `StarRating.tsx`

**Files:**
- Modify: `frame-frontend/src/pages/Dashboard.tsx`, `Coins.tsx`, `Profile.tsx`, `frame-frontend/src/components/ReviewModal.tsx`, `StarRating.tsx`

- [ ] **Step 1: Swap to kit**
  - Dashboard: stat tiles → `<Card className="p-4 flex items-center gap-3">`; chart card → `<Card>`. No `formatPrice`.
  - Coins: header → `<PageHeader icon={<CoinsIcon className="h-5 w-5 text-amber-500" />} title="我的金币" />`; balance + history cards → `<Card className="p-4">`. Preserve `我的金币`, `颗（100颗 = ¥1）`, `今日已领取`/`今日可领取（登录后自动领取）`, `金币明细`, `暂无记录`, `加载中…`.
  - Profile: `inputClass` (118) → `<Input>`; the three `<section>` cards (131, 161, 194) → `<Card className="p-5 space-y-4">`; buttons → `<Button loading={loading}>`. Preserve `Profile`, `Nickname`, `Signature`, `Save profile`, `Phone rebind`, `Change password`, etc.
  - ReviewModal: card shell → `<Card>`; textarea `border rounded p-2` → `<Textarea>`; buttons → `<Button>`. Preserve `编辑评价`/`写评价`, `取消`, `提交中…`/`提交评价`, placeholder `图文评价（可选）`.
  - StarRating: already uses `text-amber-500`/`text-slate-300` — leave as-is (it's already the newer dialect); only swap the `★` buttons to `<Button variant="ghost">` if it reads cleanly, else leave unchanged.

- [ ] **Step 2: Run the page tests**

Run: `cd frame-frontend && npx vitest run tests/Coins.test.tsx tests/Dashboard.test.tsx tests/ReviewModal.test.tsx`
Expected: PASS.

- [ ] **Step 3: Build + commit**

Run: `cd frame-frontend && npm run build`

```bash
git add frame-frontend/src/pages/Dashboard.tsx frame-frontend/src/pages/Coins.tsx frame-frontend/src/pages/Profile.tsx frame-frontend/src/components/ReviewModal.tsx frame-frontend/src/components/StarRating.tsx
git commit -m "refactor(ui): sweep Dashboard/Coins/Profile/ReviewModal to ui kit

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 14: Full frontend regression + visual check

**Files:** none (verification only)

- [ ] **Step 1: Run the full vitest suite**

Run: `cd frame-frontend && npm test`
Expected: all green (12 existing + 3 new ui tests = 15 files).

- [ ] **Step 2: Build the production bundle**

Run: `cd frame-frontend && npm run build`
Expected: success; `dist/` updated.

- [ ] **Step 3: Visual spot-check**

Run: `cd frame-frontend && npm run dev`
Open `http://localhost:5173` (requires the backend running on 8081, or just eyeball the login/register pages which render without API). Confirm: Login, Register, Products (if backend up), Checkout, Orders, Profile, Coins, Chat, Admin all render in the unified slate/brand dialect. No `text-gray-*`, no `bg-blue-500`, no raw unstyled `<select>`.
Stop dev server when done.

- [ ] **Step 4: Commit (only if any fixups were made)**

```bash
git add -A
git commit -m "fix(ui): frontend regression fixups after full sweep

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Phase 3 — Backend kits (self-contained, one per commit)

> **Backend sweep rules:** each kit is added in its own file, then its call-sites are switched in the SAME commit, then `mvn clean package -DskipTests` + the e2e suite confirm green. The e2e suite lives in `e2e/` — run `npm test` there (or the documented runner). If `mvn`/e2e can't run in the executor's env, at minimum `mvn clean compile` must pass; flag the e2e gap in `docs/superpowers/skipped-issues.md`.

### Task 15: `Money` kit + call-site sweep

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/common/Money.java`
- Modify: `CoinServiceImpl.java` (promote `COINS_PER_YUAN` + the subtotal loop), `CheckoutServiceImpl.java:150-154` (subtotal loop), `StreamConsumerService.java:267-270` (cents↔coins equivalence).

**Interfaces:**
- Produces: `common.Money` with `public static final int COINS_PER_YUAN = 100;`, `static int subtotalCents(Map<Long,Product> byId, Map<Long,Integer> qty)`, `static int centsToCoins(int cents)`, `static int coinsToCents(int coins)`.

- [ ] **Step 1: Write the kit**

```java
package com.iflytek.frame.common;

import com.iflytek.frame.entity.Product;
import java.util.Map;

/**
 * Money / coin math. The single source of the cents↔coins↔yuan relationship:
 * 100 coins = ¥1 = 100 cents (1 coin : 1 cent). Promoted out of CoinServiceImpl
 * so CheckoutServiceImpl + StreamConsumerService share it instead of re-deriving.
 */
public final class Money {
    private Money() {}

    /** 100 coins == ¥1 == 100 cents. */
    public static final int COINS_PER_YUAN = 100;

    /** Sum price*quantity across a product map. Dedupes the loop in Checkout/Coin services. */
    public static int subtotalCents(Map<Long, Product> byId, Map<Long, Integer> qty) {
        int total = 0;
        for (Map.Entry<Long, Integer> e : qty.entrySet()) {
            Product p = byId.get(e.getKey());
            if (p != null) total += p.getPriceInCents() * e.getValue();
        }
        return total;
    }

    /** coins → cents (1:1). Named for symmetry, reads better than a bare cast. */
    public static int coinsToCents(int coins) {
        return coins;
    }

    /** cents → coins (1:1). */
    public static int centsToCoins(int cents) {
        return cents;
    }
}
```

- [ ] **Step 2: Switch call-sites**
  - `CoinServiceImpl.java:38` `private static final int COINS_PER_YUAN = 100;` → delete; replace uses with `Money.COINS_PER_YUAN`.
  - `CoinServiceImpl.java:113-115` subtotal loop → `int subtotalCents = Money.subtotalCents(byId, qty);` (match the exact variable names in the file).
  - `CheckoutServiceImpl.java:150-154` subtotal loop → `Money.subtotalCents(...)`.
  - `StreamConsumerService.java:267-270` → `int coinDiscountCents = (useCoins && coinAmount > 0) ? Money.coinsToCents(coinAmount) : 0; int paidInCents = totalInCents - coinDiscountCents;`

- [ ] **Step 3: Compile**

Run: `cd frame-backend && mvn clean compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/common/Money.java frame-backend/src/main/java/com/iflytek/frame/service/impl/CoinServiceImpl.java frame-backend/src/main/java/com/iflytek/frame/service/impl/CheckoutServiceImpl.java frame-backend/src/main/java/com/iflytek/frame/service/StreamConsumerService.java
git commit -m "refactor(backend): extract Money kit — shared cents/coin math (modular)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 16: `PageResults` kit + `PageQuery` derived-field move

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/common/PageResults.java`
- Modify: `PageQuery.java` (add the `keywords()`/`sortOrderSql()` derivation), `ProductServiceImpl.java:76-103` (stop re-deriving), 6 sites using the `PageResult.of(total,current,size,records)` tail.

**Interfaces:**
- Produces: `common.PageResults` with `static <T> PageResult<T> of(IPage<T> page)`. `PageQuery` gains `public List<String> keywords()` and `public String sortOrderSql()`.

- [ ] **Step 1: Write the kit**

```java
package com.iflytek.frame.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * Collapses the 6× `PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records)`
 * tail. Also the seam for future PageResult shaping.
 */
public final class PageResults {
    private PageResults() {}

    public static <T> PageResult<T> of(IPage<T> page) {
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }
}
```

- [ ] **Step 2: Move derivation onto `PageQuery`**

Read `PageQuery.java` + `ProductServiceImpl.page` (`:76-103`). Move the `kw.split("[,\\s]+")` + trim + drop-empty + `sortOrder` → `" ASC"`/`" DESC"` normalization into `PageQuery.keywords()` / `PageQuery.sortOrderSql()` methods (or getters). Update `ProductServiceImpl.page` to call `query.keywords()` / `query.sortOrderSql()` instead of inlining.

- [ ] **Step 3: Switch the 6 `PageResult.of(...)` tails to `PageResults.of(page)`**
  - `AdminOrderServiceImpl.java:40,70`, `OrderServiceImpl.java:82`, `CoinServiceImpl.java:265`, `ProductServiceImpl.java:123`, `ChatServiceImpl.java:96`.

- [ ] **Step 4: Compile**

Run: `cd frame-backend && mvn clean compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/common/PageResults.java frame-backend/src/main/java/com/iflytek/frame/util/PageQuery.java frame-backend/src/main/java/com/iflytek/frame/service/impl/ProductServiceImpl.java frame-backend/src/main/java/com/iflytek/frame/service/impl/AdminOrderServiceImpl.java frame-backend/src/main/java/com/iflytek/frame/service/impl/OrderServiceImpl.java frame-backend/src/main/java/com/iflytek/frame/service/impl/CoinServiceImpl.java frame-backend/src/main/java/com/iflytek/frame/service/impl/ChatServiceImpl.java
git commit -m "refactor(backend): PageResults kit + move keyword/sort derivation onto PageQuery

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 17: `RedisKeys` kit + call-site sweep

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/common/RedisKeys.java`
- Modify: ~11 files switching `"prefix:" + id` → `RedisKeys.prefix(id)`.

**Interfaces:**
- Produces: `common.RedisKeys` with `static String orderStatus(long id)`, `orderIdMap(String id)`, `seckill(long pid,long userId)`, `stock(long id)`, `product(long id)`, `productsPage(String key)`, `captcha(String id)`, `rebindVerified(long userId)`, `loginFail(long userId)`, `smsCode(String purpose, String phone)`, `smsCooldown(String purpose, String phone)`, `ordersPending()`.

- [ ] **Step 1: Write the kit**

```java
package com.iflytek.frame.common;

/**
 * Central Redis key builder. Replaces ~45 hand-built `"prefix:" + id` concatenations
 * across 11 files so key formats live in one place (and are grep-able). All methods
 * return the EXACT same strings the inline code produced — behavior-identical.
 */
public final class RedisKeys {
    private RedisKeys() {}

    public static String orderStatus(long id) { return "order_status:" + id; }
    public static String orderIdMap(String id) { return "order_id_map:" + id; }
    public static String seckill(long productId, long userId) { return "seckill:" + productId + ":" + userId; }
    public static String stock(long id) { return "stock:" + id; }
    public static String product(long id) { return "product:" + id; }
    public static String productsPage(String cacheKey) { return "products:page:" + cacheKey; }
    public static String captcha(String id) { return "captcha:" + id; }
    public static String rebindVerified(long userId) { return "rebind:verified:" + userId; }
    public static String loginFail(long userId) { return "login:fail:" + userId; }
    public static String smsCode(String purpose, String phone) { return "sms:code:" + purpose + ":" + phone; }
    public static String smsCooldown(String purpose, String phone) { return "sms:cooldown:" + purpose + ":" + phone; }
    public static String ordersPending() { return "orders:pending"; }
}
```

- [ ] **Step 2: Switch call-sites (mechanical find-replace per file)**

Files + replacements (verify each against the audit's file:line list):
- `OrderServiceImpl.java:32,62,257`, `CheckoutServiceImpl.java:125,137,160`, `StreamConsumerService.java:278,280,299,302,303,315,350,352,354,371,375,382,384,85,177`, `OrderStockReleaseService.java:53`, `StockGateServiceImpl.java:47,66,76,114`, `ProductServiceImpl.java:51,128,140,147,191,204,224`, `CaptchaServiceImpl.java:30,37`, `PhoneRebindServiceImpl.java:53,62,69`, `LoginAttemptServiceImpl.java:60` (it has a local `key()` — switch the local helper to delegate to `RedisKeys.loginFail`), `LoggingVerificationProvider.java:38,43,44,59-61`.

> ⚠ The executor MUST read each cited line and confirm the literal matches the kit's output before replacing. e.g. `CaptchaServiceImpl:37` has a `startsWith("captcha:")` prefix-tolerance hack — keep that working (the kit still prefixes `captcha:`).

- [ ] **Step 3: Compile**

Run: `cd frame-backend && mvn clean compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/common/RedisKeys.java <all modified service files>
git commit -m "refactor(backend): RedisKeys kit — centralize 11 key prefixes (modular)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 18: Ownership + current-user helpers

**Files:**
- Modify: `OrderService.java` + `OrderServiceImpl.java` (add `requireOwnedOrder`), `UserService.java` + `UserServiceImpl.java` (add `requireCurrentUser`/`requireUserMatching`), then switch the 6× ownership guard + 4× `findByUsername(currentUsername)` dance.

**Interfaces:**
- Produces: `OrderService.requireOwnedOrder(orderId, userId): Order` (throws `BusinessException(NOT_FOUND,"order not found")`); `UserService.requireUserMatching(username, userId): User` (throws `BusinessException(USER_NOT_FOUND,"user not found")`).

- [ ] **Step 1: Add the helper methods**

In `OrderService` (interface) + `OrderServiceImpl`:
```java
/** Load an order and assert it belongs to userId, else NOT_FOUND. Replaces 6× inline guard. */
Order requireOwnedOrder(Long orderId, Long userId);
// impl:
@Override
public Order requireOwnedOrder(Long orderId, Long userId) {
    Order o = baseMapper.selectById(orderId);
    if (o == null || !o.getUserId().equals(userId)) {
        throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
    }
    return o;
}
```
In `UserService` + `UserServiceImpl`:
```java
/** Re-fetch the current user by username and assert id matches, else USER_NOT_FOUND. */
User requireUserMatching(String username, Long userId);
// impl:
@Override
public User requireUserMatching(String username, Long userId) {
    User u = findByUsername(username);
    if (u == null || !u.getId().equals(userId)) {
        throw new BusinessException(ResultCode.USER_NOT_FOUND, "user not found");
    }
    return u;
}
```

- [ ] **Step 2: Switch call-sites**
  - `OrderServiceImpl.java:74,96,123,144,160,189,268` → `Order o = requireOwnedOrder(id, userId);` (preserve the exact local var name each method used).
  - `AdminOrderServiceImpl.java:83` → its ownership check (confirm semantics match — admin fetch may differ; if so, leave admin as-is and only dedupe the 6 user-side guards).
  - `PhoneRebindServiceImpl.java:39-42,58-61`, `ProfileServiceImpl.java:31-34`, `PasswordServiceImpl.java:27-31` → `User u = userService.requireUserMatching(SecurityUtils.currentUsername(), userId);`.

- [ ] **Step 3: Compile**

Run: `cd frame-backend && mvn clean compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add <OrderService, OrderServiceImpl, UserService, UserServiceImpl, PhoneRebindServiceImpl, ProfileServiceImpl, PasswordServiceImpl, AdminOrderServiceImpl>
git commit -m "refactor(backend): requireOwnedOrder + requireUserMatching helpers

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 19: `IdempotentInsert` kit

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/common/IdempotentInsert.java`
- Modify: `CoinServiceImpl.java:59-64,88-93,223-227`, `ChatServiceImpl.java:40-50`, `StreamConsumerService.java:273-290`.

**Interfaces:**
- Produces: `common.IdempotentInsert` with `static <T> T insertOr(Supplier<T> insert, Supplier<T> onDuplicate)` — runs `insert`; on `DataIntegrityViolationException` returns `onDuplicate.get()`.

- [ ] **Step 1: Write the kit**

```java
package com.iflytek.frame.common;

import org.springframework.dao.DataIntegrityViolationException;
import java.util.function.Supplier;

/**
 * Collapses the 5× `try { insert } catch (DataIntegrityViolationException) { recover }`
 * idempotency pattern. The recovery body differs per call site, so it's passed as a
 * Supplier. Behavior-identical to the inline try/catch it replaces.
 */
public final class IdempotentInsert {
    private IdempotentInsert() {}

    /** Run `insert`; if it violates a unique constraint, return `onDuplicate.get()`. */
    public static <T> T insertOr(Supplier<T> insert, Supplier<T> onDuplicate) {
        try {
            return insert.get();
        } catch (DataIntegrityViolationException e) {
            return onDuplicate.get();
        }
    }
}
```

- [ ] **Step 2: Switch the 5 call-sites**

e.g. `CoinServiceImpl.java:59-64` (awardDailyLogin "duplicate-key no-op"):
```java
IdempotentInsert.insertOr(() -> save(ledger), () -> null);
```
Preserve the existing log lines (wrap the lambda to `log.warn` first if the original logged — read each site). `ChatServiceImpl` `findOrCreateOpenSession` returns the re-selected session on duplicate. `StreamConsumerService` keeps its recredit path.

- [ ] **Step 3: Compile**

Run: `cd frame-backend && mvn clean compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/common/IdempotentInsert.java <CoinServiceImpl, ChatServiceImpl, StreamConsumerService>
git commit -m "refactor(backend): IdempotentInsert kit — dedupe 5× duplicate-key guards

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 20: Backend behavior improvements (4.6) — raw-exception fix

**Files:**
- Modify: `CheckoutServiceImpl.java:192`, `StreamConsumerService.java:388`.

- [ ] **Step 1: Replace `throw new RuntimeException(e)` with `BusinessException`**

`CheckoutServiceImpl.java:192` and `StreamConsumerService.java:388` (both wrap a Jackson `JsonProcessingException`):
```java
} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
    throw new BusinessException(ResultCode.INTERNAL_ERROR, "serialization failed", e);
}
```
(Confirm `ResultCode.INTERNAL_ERROR` exists — read `ResultCode.java`; if the code uses a different name like `INTERNAL_SERVER_ERROR`, use that.)

- [ ] **Step 2: Compile + run e2e**

Run: `cd frame-backend && mvn clean package -DskipTests -q`
Then run the e2e suite (`cd e2e && <runner>` — check `e2e/` for the script). Expected: still green (the error path isn't exercised by happy-path e2e, so 67/67 should hold).

- [ ] **Step 3: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/impl/CheckoutServiceImpl.java frame-backend/src/main/java/com/iflytek/frame/service/StreamConsumerService.java
git commit -m "fix(backend): raw RuntimeException → BusinessException on serialization failure

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 21: Backend behavior improvements (4.6) — `@PreAuthorize` migration

**Files:**
- Modify: `SecurityConfig.java:108-130` (keep URL matchers as defense-in-depth), admin controllers (`AdminOrderController`, `AdminSaleController`, `AdminOperationLogController`, `AdminChatController`) add `@PreAuthorize("hasRole('ADMIN')")` per method.
- Test: add a security test asserting non-admin → 403 on an admin endpoint.

- [ ] **Step 1: Confirm method security is enabled**

Read `SecurityConfig.java`. Ensure `@EnableMethodSecurity` is present (Spring Boot 3 equivalent of `@EnableGlobalMethodSecurity(prePostEnabled=true)`). If absent, add it to the `SecurityFilterChain`-bearing config class.

- [ ] **Step 2: Add `@PreAuthorize` to each admin controller method**

For each `@PostMapping`/`@GetMapping`/etc. in the 4 admin controllers, annotate:
```java
@PreAuthorize("hasRole('ADMIN')")
```
Keep the existing URL matchers in `SecurityConfig` (defense-in-depth — do NOT remove them in this task).

- [ ] **Step 3: Write a security test**

Add a test (in `frame-backend/src/test/...`) that, with a non-admin JWT, `GET /admin/...` returns 403. Follow the existing test style in the backend test sources (read one existing controller test to mirror).

- [ ] **Step 4: Compile + test + e2e**

Run: `cd frame-backend && mvn clean package -q` (runs unit tests) + e2e suite.
Expected: green. If any existing e2e test logs in as admin and hits `/admin/**`, it still passes.

- [ ] **Step 5: Commit**

```bash
git add <SecurityConfig, 4 admin controllers, new test>
git commit -m "feat(backend): @PreAuthorize on admin endpoints (defense-in-depth, URL matchers retained)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 22: Backend behavior improvements (4.6) — pagination unification (API-breaking)

**Files:**
- Modify: `AdminOperationLogController.java:25-31`, `AdminChatController.java:25-26`, `ChatController.java:24-25` → `@Valid @ParameterObject PageQuery`; their service impls take `PageQuery`.
- Modify: matching frontend call sites (`Admin.tsx` operation-log table if present, `Chat.tsx`, `Admin.tsx` chat inbox) + e2e URLs.
- Test: update e2e request URLs (`page`→`current`).

> ⚠ This is the one **API-breaking** change. Do it caller+callee together. If a non-frontend caller exists, STOP and log to `docs/superpowers/skipped-issues.md`.

- [ ] **Step 1: Read the 3 controllers + their services + the frontend api functions that call them**

Map each endpoint's current param names (`page`/`size` or `current`/`size`) and the frontend `api/*.ts` functions + the e2e URLs that hit them.

- [ ] **Step 2: Switch controllers to `PageQuery`**

```java
public Result<PageResult<...>> list(@Valid @ParameterObject PageQuery query) { ... }
```
Service impls build the `Page` from `query.getCurrent()`/`query.getSize()`.

- [ ] **Step 3: Update frontend api call sites**

In `api/order.ts`/`api/chat.ts` (or wherever), change the query params sent from `{page, size}` to `{current, size}` to match `PageQuery`. Update `Chat.tsx`/`Admin.tsx` if they construct the query inline.

- [ ] **Step 4: Update e2e URLs**

In `e2e/`, find requests to the 3 endpoints and change `?page=1&size=50` → `?current=1&size=50`.

- [ ] **Step 5: Build + full test + e2e**

Run: `cd frame-backend && mvn clean package -q` + `cd frame-frontend && npm run build && npm test` + e2e suite.
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add <3 controllers, their services, frontend api + pages, e2e urls>
git commit -m "feat(backend): unify 3 controllers on PageQuery (current/size); update callers + e2e

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 23: Backend behavior improvements (4.6) — `@Phone`/`@SmsCode` validators

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/validation/Phone.java` + `PhoneValidator.java`, `SmsCode.java` + `SmsCodeValidator.java`.
- Modify: `RegisterRequest`, `ForgotPasswordRequest`, `RebindConfirmRequest`, `SendCodeRequest` (annotate phone/code), `RebindVerifyOldRequest` (add `@NotBlank` to code), plus the controllers that need `@Valid` (`ProfileController:53` rebindVerifyOld, etc.).

- [ ] **Step 1: Write the two annotations + validators**

```java
// validation/Phone.java
package com.iflytek.frame.validation;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PhoneValidator.class)
public @interface Phone {
    String message() default "invalid phone number";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
// validation/PhoneValidator.java — 11-digit numeric
// validation/SmsCode.java + SmsCodeValidator.java — 6-digit numeric
```
(Validator impls: `isValid` returns `value == null || value.matches("\\d{11}")` for Phone, `\\d{6}` for SmsCode, matching the existing inline `id.matches("\\d{11}")` at `AuthServiceImpl:32` and `pattern="\\d{11}"` on the frontend.)

- [ ] **Step 2: Annotate DTOs + add `@Valid` to controllers**

`RegisterRequest.phone` → `@Phone`; `.code` → `@SmsCode`. Same for `ForgotPasswordRequest`, `RebindConfirmRequest.newPhone`→`@Phone`, `.code`→`@SmsCode`, `SendCodeRequest.phone`→`@Phone`. `RebindVerifyOldRequest.code` → `@NotBlank`. Ensure `ProfileController:53` has `@Valid`.

- [ ] **Step 3: Build + test + e2e**

Run: `cd frame-backend && mvn clean package -q` + e2e suite.
Expected: green. (If e2e sends malformed phone, it'd now 400 — but e2e uses valid phones.)

- [ ] **Step 4: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/validation/ <DTOs> <ProfileController>
git commit -m "feat(backend): @Phone/@SmsCode validators; annotate DTOs; @Valid on rebind

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 24: Backend full regression + skipped-issues log

**Files:** none (verification) + `docs/superpowers/skipped-issues.md` (create if any gap).

- [ ] **Step 1: Backend build + full test**

Run: `cd frame-backend && mvn clean package -q`
Expected: BUILD SUCCESS + unit tests green.

- [ ] **Step 2: e2e suite (the 67/67)**

Run the e2e suite per `e2e/` runner.
Expected: all green. If the e2e can't run in the executor env, log to `docs/superpowers/skipped-issues.md`: "e2e not run — executor env limitation; verify before deploy."

- [ ] **Step 3: Log any deferred items**

Create/update `docs/superpowers/skipped-issues.md` listing anything skipped (e.g. "AdminOrderServiceImpl admin-ownership check left as-is — semantics differed").

```bash
git add docs/superpowers/skipped-issues.md
git commit -m "docs: skipped-issues log for backend consolidation

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Phase 4 — Cloud deployment learning journey to `172.29.231.145`

> **Constraint:** read-only recon; pick non-conflicting ports/paths/DB name/systemd unit; never touch pre-existing services/data. This phase is a guided runbook the executor fills in as it learns the server's actual state.

### Task 25: Read-only recon + runbook skeleton

**Files:**
- Create: `deploy/cloud-145-deploy.md`

- [ ] **Step 1: SSH + survey (READ-ONLY)**

Run (the executor should suggest the user run the SSH themselves if key trust prompts block automation; use `!` prefix guidance):
```bash
ssh root@172.29.231.145   # password: Bigdata_2023
# On the server — survey only, change nothing:
uname -a
ss -tlnp                          # what ports are in use
systemctl list-units --type=service --state=running   # running services
ufw status                        # firewall
ls /opt /var/www 2>/dev/null      # existing app dirs
nginx -T 2>/dev/null | head -50   # existing nginx sites
docker ps 2>/dev/null             # containers
which java mvn mariadb redis-server nginx node npm 2>/dev/null   # installed runtimes
df -h                             # free disk
```
Record findings in `deploy/cloud-145-deploy.md`.

- [ ] **Step 2: Choose non-conflicting ports + own dirs + own DB name**

Based on recon, in the runbook decide:
- Backend port (e.g. `18081` if 8081 is taken; else `8081`).
- Frontend nginx port (e.g. `8080` if 80 is taken; else `80`) — use a high port if not root-bound, or reuse 80 only if free.
- DB name (e.g. `frame_shop_db` to avoid colliding with any existing `frame_db`).
- systemd unit name (`frame-shop-backend.service` — distinct from any existing `frame-backend.service`).
- Own dirs: `/opt/frame-shop/`, `/var/www/frame-shop-frontend/`.

- [ ] **Step 3: Commit the runbook skeleton**

```bash
git add deploy/cloud-145-deploy.md
git commit -m "docs(deploy): recon + deploy runbook for 172.29.231.145

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 26: Provision runtime + DB (own DB, no disturbance)

**Files:** none on this host (server-side); update runbook.

- [ ] **Step 1: Install missing runtime only (don't touch existing)**

On the server (only if `which java` etc. showed them missing):
```bash
sudo apt update && sudo apt install -y openjdk-21-jdk-headless mariadb-server redis-server nginx
```
If MariaDB/Redis/nginx already exist and serve other things, do NOT reconfigure them — install only what's missing, and run Frame Shop's own instance on distinct ports/configs.

- [ ] **Step 2: Create the own DB + app user + load schema**

```bash
sudo mariadb -u root -p < /opt/frame-shop/deploy/schema-prod.sql
# edit schema-prod.sql's CREATE USER password to a fresh one for this deploy
```
Use the repo's `deploy/schema-prod.sql` (the fixed 9-table idempotent prod schema). Own DB name + own app user; do NOT touch any existing DB.

- [ ] **Step 3: Record in runbook + commit**

```bash
git add deploy/cloud-145-deploy.md
git commit -m "docs(deploy): runtime + DB provisioning steps (own DB, non-conflicting)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 27: Build + transfer the improved app

**Files:** none (build + scp).

- [ ] **Step 1: Build on Windows**

Run:
```bash
cd frame-backend && mvn clean package -DskipTests   # → target/frame-backend-0.0.1-SNAPSHOT.jar
cd ../frame-frontend && npm run build               # → dist/
```

- [ ] **Step 2: Transfer to the server (own dirs)**

```bash
SERVER=root@172.29.231.145
scp frame-backend/target/frame-backend-0.0.1-SNAPSHOT.jar $SERVER:/opt/frame-shop/
scp -r frame-frontend/dist/* $SERVER:/var/www/frame-shop-frontend/
scp deploy/* $SERVER:/opt/frame-shop/deploy/
```

- [ ] **Step 3: Commit runbook updates**

```bash
git add deploy/cloud-145-deploy.md
git commit -m "docs(deploy): build + transfer steps

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 28: Configure + start backend (own systemd unit + ports)

**Files:** none on host; `deploy/frame-shop-backend.service` (new, own unit).

- [ ] **Step 1: Create the own systemd unit**

Copy `deploy/frame-backend.service` → `deploy/frame-shop-backend.service`; edit:
- `Environment=FRAME_DB_PASSWORD=<the own DB password>`
- `Environment=FRAME_JWT_SECRET=<fresh 32+ char secret>`
- `Environment=FRAME_REDIS_PASSWORD=<own or blank>`
- `Environment=FRAME_CORS_ORIGINS=http://172.29.231.145:<frontend-port>`
- `Environment=FRAME_SERVER_PORT=<own backend port>` (if the backend reads a port env; else set in `application-prod.yml` / `--server.port=`)

- [ ] **Step 2: Install + start**

```bash
sudo cp /opt/frame-shop/deploy/frame-shop-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable frame-shop-backend
sudo systemctl start frame-shop-backend
sudo systemctl status frame-shop-backend
sudo journalctl -u frame-shop-backend -f   # Ctrl+C to exit
```

- [ ] **Step 3: Smoke-test the API**

```bash
curl http://localhost:<own-backend-port>/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```
Expected: `{"code":200,...,"data":{"token":...,"role":"ADMIN"}}`.

- [ ] **Step 4: Commit the own unit**

```bash
git add deploy/frame-shop-backend.service deploy/cloud-145-deploy.md
git commit -m "deploy: own systemd unit for frame-shop on 172.29.231.145 (non-conflicting)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 29: Configure + reload nginx (own site, own port — don't clobber existing)

**Files:** `deploy/nginx-cloud-145.conf` (new, own server block).

- [ ] **Step 1: Write an own nginx site config**

Copy `deploy/nginx.conf` → `deploy/nginx-cloud-145.conf`; edit:
- `listen <own-frontend-port>;`
- `root /var/www/frame-shop-frontend;`
- `proxy_pass http://127.0.0.1:<own-backend-port>/;` (both `/api/` and `/api/checkout`)
- `/uploads/` alias → `/var/lib/frame-shop/uploads/;`

- [ ] **Step 2: Install without removing the default/existing site**

```bash
sudo cp /opt/frame-shop/deploy/nginx-cloud-145.conf /etc/nginx/sites-available/frame-shop-cloud
sudo ln -sf /etc/nginx/sites-available/frame-shop-cloud /etc/nginx/sites-enabled/
# DO NOT remove sites-enabled/default if other apps use it
sudo nginx -t
sudo systemctl reload nginx
```

- [ ] **Step 3: Open the firewall port (only the own port)**

```bash
sudo ufw allow <own-frontend-port>/tcp
sudo ufw status
```

- [ ] **Step 4: Commit**

```bash
git add deploy/nginx-cloud-145.conf deploy/cloud-145-deploy.md
git commit -m "deploy: own nginx site for frame-shop on 172.29.231.145 (own port, no clobber)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 30: End-to-end verify + confirm no disturbance to existing services

**Files:** none (verification).

- [ ] **Step 1: Browser verification**

Open `http://172.29.231.145:<own-frontend-port>`. Register → Login as `admin`/`admin123` → browse Products → open a flash sale → Checkout → Pay. Confirm the improved UI renders in the unified dialect.

- [ ] **Step 2: Confirm pre-existing services still up**

```bash
sudo ss -tlnp                        # the ports that were listening before recon are still there
sudo systemctl list-units --state=running   # the services that were running are still running
```

- [ ] **Step 3: Record final state in runbook + commit**

```bash
git add deploy/cloud-145-deploy.md
git commit -m "docs(deploy): final verification — frame-shop live on 145, existing services intact

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Self-Review (completed by plan author)

**1. Spec coverage:**
- §1.1 frontend duplication (input string ×6, STATUS_COLORS ×3→1, formatPrice in page file, card shell ×15, PageHeader ×4, older dialect) → Tasks 1–14. ✅
- §3.1 the 11-component kit + lib → Tasks 1–5. ✅
- §3.4 frontend↔backend seam (keep intact) → enforced via Global Constraints (kit takes primitives/types). ✅
- §4.1 RedisKeys → Task 17. ✅
- §4.2 Money → Task 15. ✅
- §4.3 PageResults + PageQuery move → Task 16. ✅
- §4.4 ownership/current-user helpers → Task 18. ✅
- §4.5 IdempotentInsert → Task 19. ✅
- §4.6 raw-exception → Task 20; @PreAuthorize → Task 21; pagination unification → Task 22; validators → Task 23. ✅
- §4.7 combine-strategy → honored via one-kit-per-commit + self-contained kits (stated in Global Constraints + each backend task). ✅
- §5 deploy recon/ports/harden/transfer/verify → Tasks 25–30. ✅
- §6 testing (build + vitest + mvn + e2e + curl) → built into each task's verification. ✅
- §8 out-of-scope (no theme, no DB schema, no pre-existing disturbance) → enforced. ✅

**2. Placeholder scan:** No TBD/TODO/"add appropriate". One genuine uncertainty flagged in-task: the `¥` vs `$` rendering in Products/ProductDetailPage — resolved in Task 6 Step 2 (keep `¥` inline, only move the *definition* of `formatPrice`). Backend `ResultCode.INTERNAL_ERROR` exact name → flagged "read ResultCode.java" in Task 20. e2e runner command → flagged "check e2e/" in Tasks 20–24. These are executor-verification steps, not missing plan content.

**3. Type consistency:** `cn(...classes: Array<string|false|null|undefined>)` matches usage in Button/Card/etc. `StatusBadgeProps.status: OrderStatus` matches the `@/api/types` export. `Money.subtotalCents(Map<Long,Product>, Map<Long,Integer>)` matches the audit's described call sites. `PageResults.of(IPage<T>)` returns `PageResult<T>` matching `common.PageResult`. Kit method names (`requireOwnedOrder`, `requireUserMatching`, `RedisKeys.orderStatus`) used consistently across the task that defines them and the task that consumes them.
