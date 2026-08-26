import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { CartProvider } from '@/store/CartContext'
import Checkout from '@/pages/Checkout'

/**
 * Three-stage checkout flow test.
 *
 * Stage 1: form → checkout() returns 202 {orderId, status:"queued"}
 * Stage 2: poll getOrder(orderId) — first returns the synthetic placeholder
 *          (totalInCents=0), then the real PENDING row (totalInCents>0)
 * Stage 3: payOrder(orderId) → PAID confirmation
 *
 * The checkout/getOrder/payOrder modules are mocked so the test doesn't need the backend.
 */

vi.mock('@/api/order', () => ({
  checkout: vi.fn(),
  getOrder: vi.fn(),
  payOrder: vi.fn(),
  cancelOrder: vi.fn(),
  getMyOrders: vi.fn(),
}))

vi.mock('@/api/coin', () => ({
  getCoinBalance: vi.fn(),
  getCoinHistory: vi.fn(),
}))

import { checkout, getOrder, payOrder } from '@/api/order'
import { getCoinBalance } from '@/api/coin'
import type { CheckoutResponse, Order } from '@/api/types'

function mockCheckout(overrides: Partial<CheckoutResponse> = {}): CheckoutResponse {
  return { orderId: 4242, status: 'queued', ...overrides }
}

function syntheticQueuedOrder(orderId: number): Order {
  // Matches the backend's getById synthetic placeholder: totalInCents=0, status=PENDING
  return {
    id: orderId,
    userId: 1,
    customerName: '',
    customerEmail: '',
    shippingAddress: '',
    totalInCents: 0,
    paidInCents: 0,
    status: 'PENDING',
    idempotencyKey: '',
  }
}

function realPendingOrder(orderId: number): Order {
  return {
    id: orderId,
    userId: 1,
    customerName: 'Test User',
    customerEmail: 'test@example.com',
    shippingAddress: '123 St',
    totalInCents: 2500,
    paidInCents: 2500,
    status: 'PENDING',
    idempotencyKey: 'abc',
  }
}

function renderCheckout() {
  return render(
    <MemoryRouter>
      <CartProvider>
        <Checkout />
      </CartProvider>
    </MemoryRouter>,
  )
}

/** Seed the cart via localStorage so Checkout sees a non-empty cart on first render. */
function seedCart() {
  const cart = {
    items: [{ productId: 1, name: 'Widget', priceInCents: 2500, quantity: 1, stock: 5, isFlashSale: false }],
  }
  localStorage.setItem('frame-cart', JSON.stringify(cart))
}

/** Seed a flash-sale cart item — coins UI must be gated off. */
function seedFlashCart() {
  const cart = {
    items: [{ productId: 2, name: 'Flash Gizmo', priceInCents: 999, quantity: 1, stock: 10, isFlashSale: true }],
  }
  localStorage.setItem('frame-cart', JSON.stringify(cart))
}

describe('Checkout three-stage flow', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.clearAllMocks()
    localStorage.clear()
    seedCart()
    // Default coin-balance mock so the fetch-on-mount effect in the existing
    // tests (which don't exercise coins) resolves cleanly. Individual tests
    // override this with their own mockResolvedValue.
    vi.mocked(getCoinBalance).mockResolvedValue({ balance: 0, claimedToday: false, expiringSoon: 0 })
  })

  afterEach(() => {
    vi.useRealTimers()
    localStorage.clear()
  })

  it('shows the empty-cart notice when there are no items', () => {
    localStorage.clear()
    renderCheckout()
    expect(screen.getByText(/购物车为空/)).toBeInTheDocument()
  })

  it('renders a required phone field + the three payment-method radios', () => {
    renderCheckout()
    const phoneInput = screen.getByLabelText(/手机号/)
    expect(phoneInput).toHaveAttribute('required')
    // All three payment-method radios are present (grouped by name).
    const radios = screen.getAllByRole('radio', { name: /微信|支付宝|银行卡/i })
    expect(radios).toHaveLength(3)
    // Each radio shares the same name so they're mutually exclusive.
    expect(new Set(radios.map((r) => (r as HTMLInputElement).name)).size).toBe(1)
    // WECHAT (微信) is selected by default.
    expect(screen.getByLabelText(/微信/i)).toBeChecked()
  })

  it('stage 1 → 2 → 3: form → queued (poll) → pending (pay) → paid', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const mockedCheckout = vi.mocked(checkout)
    const mockedGetOrder = vi.mocked(getOrder)
    const mockedPayOrder = vi.mocked(payOrder)

    mockedCheckout.mockResolvedValue(mockCheckout())
    // First poll: synthetic placeholder (queued). Second poll: real PENDING row.
    mockedGetOrder
      .mockResolvedValueOnce(syntheticQueuedOrder(4242))
      .mockResolvedValueOnce(realPendingOrder(4242))
    mockedPayOrder.mockResolvedValue({ ...realPendingOrder(4242), status: 'PAID' })

    renderCheckout()

    // Stage 1: fill the form and submit.
    expect(screen.getByText(/提交订单/)).toBeInTheDocument()
    await user.type(screen.getByLabelText(/姓名/), 'Test User')
    await user.type(screen.getByLabelText(/邮箱/), 'test@example.com')
    await user.type(screen.getByLabelText(/收货地址/), '123 St')
    await user.type(screen.getByLabelText(/手机号/), '13800138000')
    // Payment method defaults to WECHAT (微信); select 支付宝 (ALIPAY) to
    // prove the radio drives the submitted payload.
    await user.click(screen.getByLabelText(/支付宝/i))

    await user.click(screen.getByRole('button', { name: /提交订单/ }))

    // Stage 2: checkout() called → shows "queued" spinner.
    expect(mockedCheckout).toHaveBeenCalledTimes(1)
    // The checkout payload must include the required phone + payment method.
    expect(mockedCheckout).toHaveBeenCalledWith(
      expect.objectContaining({
        customerPhone: '13800138000',
        paymentMethod: 'ALIPAY',
      }),
    )
    await waitFor(() => {
      expect(screen.getByText(/正在锁定库存/)).toBeInTheDocument()
    })
    expect(screen.getByText(/订单 #4242 已排队/)).toBeInTheDocument()

    // Advance fake timers past the 1s poll interval to trigger the first poll
    // (synthetic placeholder — should keep polling). The immediate poll happens
    // on mount of stage 2; the interval fires every 1s.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100)
    })

    // Advance again to trigger the second poll → real PENDING row.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100)
    })

    // Stage 3a: resolved PENDING → pay UI shows.
    await waitFor(() => {
      expect(screen.getByText(/库存已锁定/)).toBeInTheDocument()
    })
    // The pay button shows the amount (more specific than getByText which
    // would match both the total row and the button).
    // formatPrice now returns CNY (¥), so the amount renders as ¥25.00.
    expect(screen.getByRole('button', { name: /支付 ¥25\.00/ })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /支付 ¥25\.00/ }))

    // Stage 3b: paid → confirmation.
    expect(mockedPayOrder).toHaveBeenCalledWith(4242)
    await waitFor(() => {
      expect(screen.getByText(/支付成功/)).toBeInTheDocument()
    })
  })

  it('stage 2 → FAILED: shows retry on a failed order', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const mockedCheckout = vi.mocked(checkout)
    const mockedGetOrder = vi.mocked(getOrder)

    mockedCheckout.mockResolvedValue(mockCheckout({ orderId: 7777 }))
    // First poll: synthetic. Second: FAILED real row.
    mockedGetOrder
      .mockResolvedValueOnce(syntheticQueuedOrder(7777))
      .mockResolvedValueOnce({
        ...realPendingOrder(7777),
        status: 'FAILED',
        totalInCents: 2500,
      })

    renderCheckout()

    await user.type(screen.getByLabelText(/姓名/), 'Test User')
    await user.type(screen.getByLabelText(/邮箱/), 'test@example.com')
    await user.type(screen.getByLabelText(/收货地址/), '123 St')
    await user.type(screen.getByLabelText(/手机号/), '13800138000')
    await user.click(screen.getByRole('button', { name: /提交订单/ }))

    await waitFor(() => {
      expect(screen.getByText(/订单 #7777 已排队/)).toBeInTheDocument()
    })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100)
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100)
    })

    await waitFor(() => {
      expect(screen.getByText(/下单失败/)).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: /重试/ })).toBeInTheDocument()
  })

  it('coins checkbox disabled when cart has a flash-sale item', async () => {
    seedFlashCart()
    vi.mocked(getCoinBalance).mockResolvedValue({ balance: 500, claimedToday: true, expiringSoon: 0 })
    renderCheckout()

    // The flash-sale block shows the "no coins for flash items" message instead
    // of the checkbox.
    await waitFor(() => {
      expect(screen.getByText(/折扣商品无法使用金币/)).toBeInTheDocument()
    })
    // The 使用金币 checkbox is NOT rendered for flash carts.
    expect(screen.queryByLabelText(/使用金币/)).not.toBeInTheDocument()
  })

  it('submitting with useCoins includes coinAmount', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    vi.mocked(getCoinBalance).mockResolvedValue({ balance: 500, claimedToday: true, expiringSoon: 0 })
    vi.mocked(checkout).mockResolvedValue(mockCheckout())
    vi.mocked(getOrder)
      .mockResolvedValueOnce(syntheticQueuedOrder(4242))
      .mockResolvedValueOnce(realPendingOrder(4242))

    renderCheckout()

    await waitFor(() => {
      expect(screen.getByLabelText(/使用金币/)).toBeEnabled()
    })
    // Balance of 500 → usableCoins capped to 100-coin granularity = 500, but
    // the 50% subtotal cap (2500/2 = 1250 → floor/100*100 = 1200) is higher,
    // so usableCoins = min(1200, 500) = 500.
    await user.click(screen.getByLabelText(/使用金币/))
    const amountInput = await screen.findByLabelText(/抵扣金币数/)
    await user.clear(amountInput)
    await user.type(amountInput, '200')

    await user.type(screen.getByLabelText(/姓名/), 'Test User')
    await user.type(screen.getByLabelText(/邮箱/), 'test@example.com')
    await user.type(screen.getByLabelText(/收货地址/), '123 St')
    await user.type(screen.getByLabelText(/手机号/), '13800138000')
    await user.click(screen.getByRole('button', { name: /提交订单/ }))

    expect(vi.mocked(checkout)).toHaveBeenCalledWith(
      expect.objectContaining({ useCoins: true, coinAmount: 200 }),
    )
  })

  it('uses the Input kit for the coin amount (no raw border classes)', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    vi.mocked(getCoinBalance).mockResolvedValue({ balance: 500, claimedToday: true, expiringSoon: 0 })

    renderCheckout()

    await waitFor(() => {
      expect(screen.getByLabelText(/使用金币/)).toBeEnabled()
    })
    await user.click(screen.getByLabelText(/使用金币/))
    const amountInput = await screen.findByLabelText(/抵扣金币数/)

    // The coin-amount input uses the Input kit: it carries the kit's focus-ring
    // class (focus:ring-brand-500), not the raw hand-rolled
    // "w-24 px-2 py-1 rounded border border-slate-300" classes. The kit's own
    // BASE includes border-slate-300 + rounded-md, so the distinguishing mark
    // is the focus-ring plus the absence of the raw px-2 py-1 padding.
    expect(amountInput.className).toContain('focus:ring-brand-500')
    expect(amountInput.className).not.toContain('px-2 py-1')
  })

  it('renders the Pay button with the success variant (bg-green-600)', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    vi.mocked(checkout).mockResolvedValue(mockCheckout())
    vi.mocked(getOrder)
      .mockResolvedValueOnce(syntheticQueuedOrder(4242))
      .mockResolvedValueOnce(realPendingOrder(4242))

    renderCheckout()

    // Fill the form and submit to reach the resolved (PENDING) stage.
    await user.type(screen.getByLabelText(/姓名/), 'Test User')
    await user.type(screen.getByLabelText(/邮箱/), 'test@example.com')
    await user.type(screen.getByLabelText(/收货地址/), '123 St')
    await user.type(screen.getByLabelText(/手机号/), '13800138000')
    await user.click(screen.getByRole('button', { name: /提交订单/ }))

    // Advance through the two poll ticks: synthetic → real PENDING.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100)
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100)
    })

    await waitFor(() => {
      expect(screen.getByText(/库存已锁定/)).toBeInTheDocument()
    })

    // The Pay button uses the success variant → bg-green-600 class.
    const payButton = screen.getByRole('button', { name: /支付 ¥25\.00/ })
    expect(payButton.className).toContain('bg-green-600')
  })
})
