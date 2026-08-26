import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import Admin from '@/pages/Admin'

/**
 * Admin page (Task 16) — Tabs/Table kit consumption, image-URL form field,
 * and Chinese label sweep.
 *
 * Mocks the auth store to ADMIN, and the product/order/chat API modules so
 * the page renders without the backend.
 */
vi.mock('@/store/useAuthStore', () => ({
  useIsAdmin: () => true,
}))

vi.mock('@/api/product', () => ({
  pageProducts: vi.fn(),
  createProduct: vi.fn(),
  updateProduct: vi.fn(),
  deleteProduct: vi.fn(),
  openFlashSale: vi.fn(),
}))

vi.mock('@/api/order', () => ({
  adminListOrders: vi.fn(),
  adminShip: vi.fn(),
  adminDeliver: vi.fn(),
  adminRefundRequests: vi.fn(),
  adminApproveRefund: vi.fn(),
  adminDenyRefund: vi.fn(),
}))

vi.mock('@/api/chat', () => ({
  adminInbox: vi.fn(),
  adminHistory: vi.fn(),
}))

vi.mock('@/hooks/useChat', () => ({
  useChat: () => ({
    messages: [],
    send: vi.fn(),
    connected: false,
    setMessages: vi.fn(),
  }),
}))

import { pageProducts } from '@/api/product'
import { adminListOrders, adminRefundRequests } from '@/api/order'
import { adminInbox } from '@/api/chat'
import type { Product } from '@/api/types'

function product(overrides: Partial<Product> = {}): Product {
  return {
    id: 1,
    name: 'Red Widget',
    priceInCents: 1000,
    stock: 5,
    category: 'toys',
    isFlashSale: false,
    ...overrides,
  } as Product
}

beforeEach(() => {
  vi.clearAllMocks()
  ;(pageProducts as any).mockResolvedValue({
    total: 1,
    current: 1,
    size: 100,
    records: [product()],
  })
  ;(adminListOrders as any).mockResolvedValue({ total: 0, current: 1, size: 50, records: [] })
  ;(adminRefundRequests as any).mockResolvedValue({ total: 0, current: 1, size: 50, records: [] })
  ;(adminInbox as any).mockResolvedValue([])
})

function renderAdmin() {
  return render(
    <MemoryRouter>
      <Admin />
    </MemoryRouter>,
  )
}

describe('Admin — image-URL field + Chinese labels (Task 16)', () => {
  it('has an image URL field on the product form', async () => {
    renderAdmin()
    await waitFor(() => expect(screen.getByText('Red Widget')).toBeInTheDocument())
    expect(screen.getByPlaceholderText(/图片链接/)).toBeInTheDocument()
  })

  it('renders Chinese tab labels 商品/订单/客服', async () => {
    renderAdmin()
    await waitFor(() => expect(screen.getByRole('button', { name: '商品' })).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '订单' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '客服' })).toBeInTheDocument()
  })

  it('page heading + form titles are Chinese', async () => {
    renderAdmin()
    await waitFor(() => expect(screen.getByText('Red Widget')).toBeInTheDocument())
    expect(screen.getByRole('heading', { name: /管理后台/ })).toBeInTheDocument()
    expect(screen.getByText(/新建商品/)).toBeInTheDocument()
  })
})
