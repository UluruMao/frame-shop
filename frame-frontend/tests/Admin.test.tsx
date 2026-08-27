import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import AdminProducts from '@/pages/admin/AdminProducts'

/**
 * Admin product page (decomposed from the old Admin.tsx tabs) — image-URL form
 * field and Chinese label sweep.
 *
 * Mocks the auth store to ADMIN, and the product API so the page renders
 * without the backend. Orders/chat now live in their own pages/tests.
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

import { pageProducts } from '@/api/product'
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
})

function renderAdminProducts() {
  return render(
    <MemoryRouter>
      <AdminProducts />
    </MemoryRouter>,
  )
}

describe('AdminProducts — image-URL field + Chinese labels', () => {
  it('has an image URL field on the product form', async () => {
    renderAdminProducts()
    await waitFor(() => expect(screen.getByText('Red Widget')).toBeInTheDocument())
    expect(screen.getByPlaceholderText(/图片链接/)).toBeInTheDocument()
  })

  it('page heading + form titles are Chinese', async () => {
    renderAdminProducts()
    await waitFor(() => expect(screen.getByText('Red Widget')).toBeInTheDocument())
    expect(screen.getByRole('heading', { name: /商品管理/ })).toBeInTheDocument()
    expect(screen.getByText(/新建商品/)).toBeInTheDocument()
  })
})
