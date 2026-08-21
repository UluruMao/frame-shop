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

import { checkout, getOrder, payOrder } from '@/api/order'
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
    items: [{ productId: 1, name: 'Widget', priceInCents: 2500, quantity: 1, stock: 5 }],
  }
  localStorage.setItem('frame-cart', JSON.stringify(cart))
}

describe('Checkout three-stage flow', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.clearAllMocks()
    localStorage.clear()
    seedCart()
  })

  afterEach(() => {
    vi.useRealTimers()
    localStorage.clear()
  })

  it('shows the empty-cart notice when there are no items', () => {
    localStorage.clear()
    renderCheckout()
    expect(screen.getByText(/your cart is empty/i)).toBeInTheDocument()
  })

  it('renders a required phone field + the three payment-method radios', () => {
    renderCheckout()
    const phoneInput = screen.getByLabelText(/phone/i)
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
    expect(screen.getByText(/place order/i)).toBeInTheDocument()
    await user.type(screen.getByLabelText(/name/i), 'Test User')
    await user.type(screen.getByLabelText(/email/i), 'test@example.com')
    await user.type(screen.getByLabelText(/shipping address/i), '123 St')
    await user.type(screen.getByLabelText(/phone/i), '13800138000')
    // Payment method defaults to WECHAT (微信); select 支付宝 (ALIPAY) to
    // prove the radio drives the submitted payload.
    await user.click(screen.getByLabelText(/支付宝/i))

    await user.click(screen.getByRole('button', { name: /place order/i }))

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
      expect(screen.getByText(/reserving stock/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/order #4242 queued/i)).toBeInTheDocument()

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
      expect(screen.getByText(/stock reserved/i)).toBeInTheDocument()
    })
    // The pay button shows the amount (more specific than getByText which
    // would match both the total row and the button).
    expect(screen.getByRole('button', { name: /pay \$25\.00/i })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /pay \$25\.00/i }))

    // Stage 3b: paid → confirmation.
    expect(mockedPayOrder).toHaveBeenCalledWith(4242)
    await waitFor(() => {
      expect(screen.getByText(/payment successful/i)).toBeInTheDocument()
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

    await user.type(screen.getByLabelText(/name/i), 'Test User')
    await user.type(screen.getByLabelText(/email/i), 'test@example.com')
    await user.type(screen.getByLabelText(/shipping address/i), '123 St')
    await user.type(screen.getByLabelText(/phone/i), '13800138000')
    await user.click(screen.getByRole('button', { name: /place order/i }))

    await waitFor(() => {
      expect(screen.getByText(/order #7777 queued/i)).toBeInTheDocument()
    })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100)
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100)
    })

    await waitFor(() => {
      expect(screen.getByText(/order failed/i)).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })
})
