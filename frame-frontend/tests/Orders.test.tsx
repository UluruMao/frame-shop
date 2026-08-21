import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { CartProvider } from '@/store/CartContext'
import Orders from '@/pages/Orders'

/**
 * Orders page (Phase C) — tabs, per-status actions, refund-reason modal,
 * and expandable line-items drawer.
 *
 * The @/api/order module is mocked so the tests don't need the backend.
 */
vi.mock('@/api/order', () => ({
  getMyOrders: vi.fn(),
  cancelOrder: vi.fn(),
  cancelPaid: vi.fn(),
  requestRefund: vi.fn(),
  confirmReceipt: vi.fn(),
  getOrderItems: vi.fn(),
  payOrder: vi.fn(),
}))

vi.mock('@/api/review', () => ({
  listUnreviewedOrders: vi.fn(),
  listOrderReviews: vi.fn(),
  createReview: vi.fn(),
  updateReview: vi.fn(),
  uploadReviewImage: vi.fn(),
}))

import {
  getMyOrders,
  cancelOrder,
  cancelPaid,
  requestRefund,
  confirmReceipt,
  getOrderItems,
  payOrder,
} from '@/api/order'
import { listUnreviewedOrders, listOrderReviews } from '@/api/review'
import type { Order, OrderItem } from '@/api/types'

function order(overrides: Partial<Order> = {}): Order {
  return {
    id: 1,
    userId: 1,
    customerName: 'Test User',
    customerEmail: 'test@example.com',
    shippingAddress: '123 St',
    totalInCents: 2500,
    paidInCents: 2500,
    status: 'PENDING',
    idempotencyKey: 'abc',
    ...overrides,
  }
}

function page(records: Order[]) {
  return { total: records.length, current: 1, size: 50, records }
}

function item(overrides: Partial<OrderItem> = {}): OrderItem {
  return {
    id: 10,
    orderId: 1,
    productId: 100,
    name: 'Blue Widget',
    priceInCents: 1250,
    quantity: 2,
    ...overrides,
  }
}

function renderOrders() {
  return render(
    <MemoryRouter>
      <CartProvider>
        <Orders />
      </CartProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  ;(getMyOrders as any).mockResolvedValue(page([order()]))
  ;(getOrderItems as any).mockResolvedValue([item()])
  ;(listUnreviewedOrders as any).mockResolvedValue([])
  ;(listOrderReviews as any).mockResolvedValue([])
})

describe('Orders — tabs (Phase C)', () => {
  it('renders the 6 tabs incl. an active (clickable) 未评价 tab', async () => {
    renderOrders()
    await waitFor(() => expect(screen.getByText('全部')).toBeInTheDocument())
    expect(screen.getByText('未付款')).toBeInTheDocument()
    expect(screen.getByText('未发货')).toBeInTheDocument()
    expect(screen.getByText('未签收')).toBeInTheDocument()
    expect(screen.getByText('已完成')).toBeInTheDocument()
    expect(screen.getByText('未评价')).toBeInTheDocument()
    // The 未评价 tab is now active (no longer disabled).
    const reviewTab = screen.getByText('未评价').closest('button')
    expect(reviewTab).not.toBeDisabled()
  })

  it('clicking 未付款 calls getMyOrders with status PENDING', async () => {
    renderOrders()
    await waitFor(() => expect(getMyOrders).toHaveBeenCalledTimes(1))
    // Initial load uses no status filter (全部).
    expect(getMyOrders).toHaveBeenLastCalledWith(1, 50, undefined)

    fireEvent.click(screen.getByText('未付款'))
    await waitFor(() => {
      expect(getMyOrders).toHaveBeenLastCalledWith(1, 50, 'PENDING')
    })
  })
})

describe('Orders — per-status row actions', () => {
  it('PENDING row shows Pay + Cancel buttons; Cancel calls cancelOrder', async () => {
    ;(getMyOrders as any).mockResolvedValue(page([order({ status: 'PENDING' })]))
    renderOrders()
    await waitFor(() => expect(screen.getByText('#1')).toBeInTheDocument())

    // The row pay/cancel buttons live in the Actions cell; use exact-ish names
    // to avoid matching the 未付款 tab label.
    expect(screen.getByRole('button', { name: /^付款$/i })).toBeInTheDocument()
    const cancelBtn = screen.getByRole('button', { name: /^取消$/i })
    fireEvent.click(cancelBtn)
    await waitFor(() => expect(cancelOrder).toHaveBeenCalledWith(1))
  })

  it('PENDING Pay button calls payOrder', async () => {
    ;(getMyOrders as any).mockResolvedValue(page([order({ status: 'PENDING' })]))
    ;(payOrder as any).mockResolvedValue(order({ status: 'PAID' }))
    renderOrders()
    await waitFor(() => expect(screen.getByText('#1')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /^付款$/i }))
    await waitFor(() => expect(payOrder).toHaveBeenCalledWith(1))
  })

  it('PAID row shows Cancel (refund) button; calls cancelPaid', async () => {
    ;(getMyOrders as any).mockResolvedValue(page([order({ status: 'PAID' })]))
    renderOrders()
    await waitFor(() => expect(screen.getByText('#1')).toBeInTheDocument())

    const refundBtn = screen.getByRole('button', { name: /^退款$/i })
    fireEvent.click(refundBtn)
    await waitFor(() => expect(cancelPaid).toHaveBeenCalledWith(1))
  })

  it('SHIPPED row shows Confirm-receipt + Request-refund buttons', async () => {
    ;(getMyOrders as any).mockResolvedValue(
      page([order({ status: 'SHIPPED', trackingNumber: 'TRK123' })]),
    )
    renderOrders()
    await waitFor(() => expect(screen.getByText('#1')).toBeInTheDocument())

    expect(screen.getByRole('button', { name: /确认收货/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /申请退款/i })).toBeInTheDocument()
  })

  it('SHIPPED confirm-receipt calls confirmReceipt', async () => {
    ;(getMyOrders as any).mockResolvedValue(page([order({ status: 'SHIPPED' })]))
    ;(confirmReceipt as any).mockResolvedValue(order({ status: 'COMPLETED' }))
    renderOrders()
    await waitFor(() => expect(screen.getByText('#1')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /确认收货/i }))
    await waitFor(() => expect(confirmReceipt).toHaveBeenCalledWith(1))
  })

  it('SHIPPED request-refund opens a reason modal + calls requestRefund', async () => {
    ;(getMyOrders as any).mockResolvedValue(page([order({ status: 'SHIPPED' })]))
    ;(requestRefund as any).mockResolvedValue(order({ status: 'REFUND_PENDING' }))
    renderOrders()
    await waitFor(() => expect(screen.getByText('#1')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /申请退款/i }))
    // Modal opens with a reason input + submit button.
    const reasonInput = await screen.findByPlaceholderText(/退款原因|reason/i)
    fireEvent.change(reasonInput, { target: { value: 'damaged on arrival' } })
    fireEvent.click(screen.getByRole('button', { name: /^提交$/i }))
    await waitFor(() => expect(requestRefund).toHaveBeenCalledWith(1, 'damaged on arrival'))
  })

  it('REFUND_PENDING row shows a 退款处理中 badge', async () => {
    ;(getMyOrders as any).mockResolvedValue(page([order({ status: 'REFUND_PENDING' })]))
    renderOrders()
    await waitFor(() => expect(screen.getByText(/退款处理中/)).toBeInTheDocument())
  })
})

describe('Orders — line-items drawer', () => {
  it('clicking a row expands + calls getOrderItems + shows item names/qty', async () => {
    ;(getMyOrders as any).mockResolvedValue(page([order({ status: 'PAID' })]))
    ;(getOrderItems as any).mockResolvedValue([
      item({ name: 'Blue Widget', quantity: 2 }),
      item({ id: 11, name: 'Red Gadget', quantity: 1 }),
    ])
    renderOrders()
    await waitFor(() => expect(screen.getByText('#1')).toBeInTheDocument())

    // Click the 详情 button (or the row) to expand.
    fireEvent.click(screen.getByRole('button', { name: /详情|detail/i }))
    await waitFor(() => expect(getOrderItems).toHaveBeenCalledWith(1))
    await waitFor(() => expect(screen.getByText('Blue Widget')).toBeInTheDocument())
    expect(screen.getByText('Red Gadget')).toBeInTheDocument()
  })
})

describe('Orders — 未评价 tab + review button (Phase D)', () => {
  it('未评价 tab fetches listUnreviewedOrders instead of getMyOrders', async () => {
    ;(getMyOrders as any).mockResolvedValue(page([]))
    ;(listUnreviewedOrders as any).mockResolvedValue([])
    renderOrders()
    await waitFor(() => expect(getMyOrders).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByText('未评价'))
    await waitFor(() => expect(listUnreviewedOrders).toHaveBeenCalled())
  })

  it('drawer shows 写评价 button on a DELIVERED order item', async () => {
    const o = order({ id: 5, status: 'DELIVERED' })
    ;(getMyOrders as any).mockResolvedValue(page([o]))
    ;(getOrderItems as any).mockResolvedValue([item({ orderId: 5, productId: 100 })])
    ;(listOrderReviews as any).mockResolvedValue([])
    renderOrders()
    await waitFor(() => expect(screen.getByText('#5')).toBeInTheDocument())

    // Expand the drawer via the 详情 button.
    fireEvent.click(screen.getByRole('button', { name: /详情|detail/i }))
    await waitFor(() => expect(screen.getByText('写评价')).toBeInTheDocument())
  })
})
