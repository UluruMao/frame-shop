import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import ProductDetailPage from '@/pages/ProductDetailPage'
import { CartProvider } from '@/store/CartContext'

vi.mock('@/api/product', () => ({
  getProductDetail: vi.fn(),
  getProduct: vi.fn(),
}))

import { getProductDetail, getProduct } from '@/api/product'

const detail = (overrides: Partial<any> = {}) => ({
  id: 1,
  name: 'Red Widget',
  priceInCents: 1000,
  stock: 5,
  category: 'toys',
  isFlashSale: false,
  description: 'A fine red widget',
  sold: 4,
  avgRating: 4.0,
  ...overrides,
})

beforeEach(() => {
  vi.clearAllMocks()
  ;(getProductDetail as any).mockResolvedValue(detail())
})

const renderDetail = (path = '/products/1') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <CartProvider>
        <Routes>
          <Route path="/products/:id" element={<ProductDetailPage />} />
        </Routes>
      </CartProvider>
    </MemoryRouter>,
  )

describe('ProductDetailPage (UC000 detail)', () => {
  it('renders name, price, description, sold, avgRating', async () => {
    renderDetail()
    await waitFor(() => expect(screen.getByText('Red Widget')).toBeInTheDocument())
    expect(screen.getByText(/A fine red widget/)).toBeInTheDocument()
    expect(screen.getByText(/已售 4/)).toBeInTheDocument()
    expect(screen.getByText(/评分 4\.0/)).toBeInTheDocument()
  })

  it('renders a flash-sale countdown when flashSaleAt is future', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    ;(getProductDetail as any).mockResolvedValue(detail({ isFlashSale: true, flashSaleAt: new Date(Date.now() + 3600_000).toISOString() }))
    renderDetail()
    await waitFor(() => expect(screen.getByText(/Sale opens in|秒杀即将开始|倒计时/i)).toBeInTheDocument())
    vi.useRealTimers()
  })

  it('polls live stock when flashSaleAt is past', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    ;(getProductDetail as any).mockResolvedValue(detail({ isFlashSale: true, flashSaleAt: new Date(Date.now() - 1000).toISOString(), stock: 50 }))
    ;(getProduct as any).mockResolvedValue(detail({ stock: 30 }))
    renderDetail()
    await waitFor(() => expect(getProduct).toHaveBeenCalledWith(1))
    await act(async () => { await vi.advanceTimersByTimeAsync(4100) })
    expect((getProduct as any).mock.calls.length).toBeGreaterThanOrEqual(2)
    vi.useRealTimers()
  })

  it('redirects a guest to /login on add-to-cart', async () => {
    // guest = no token in the auth store. Wrap in a router that has a /login
    // catch-all so we can assert the navigation actually happened (UC000).
    render(
      <MemoryRouter initialEntries={['/products/1']}>
        <CartProvider>
          <Routes>
            <Route path="/products/:id" element={<ProductDetailPage />} />
            <Route path="/login" element={<div>login page</div>} />
          </Routes>
        </CartProvider>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText('Red Widget')).toBeInTheDocument())
    const btn = screen.getByRole('button', { name: /加入购物车|add to cart/i })
    expect(btn).toBeInTheDocument()
    // The add-to-cart button navigates to /login when no token.
    await act(async () => { btn.click() })
    expect(screen.getByText('login page')).toBeInTheDocument()
  })

  it('ticks the flash-sale countdown down each second', async () => {
    // shouldAdvanceTime lets the initial getProductDetail promise resolve, but the
    // countdown only changes after 1s if a setInterval re-renders the component
    // (without the fix there is no interval, so the displayed text is frozen).
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const flashSaleAt = new Date(Date.now() + 3600_000).toISOString()
    ;(getProductDetail as any).mockResolvedValue(detail({ isFlashSale: true, flashSaleAt }))
    renderDetail()
    await waitFor(() => expect(screen.getByText(/秒杀即将开始/)).toBeInTheDocument())
    const before = screen.getByText(/秒杀即将开始/).textContent
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    const after = screen.getByText(/秒杀即将开始/).textContent
    expect(before).not.toEqual(after)
    vi.useRealTimers()
  })

  it('renders 商品不存在 when the product is not found', async () => {
    // The axios interceptor (src/api/request.ts) rejects with a bare `new Error(message)`
    // (no `.response` property) — exercise the REAL interceptor shape so this test
    // actually guards the not-found detection in ProductDetailPage.
    ;(getProductDetail as any).mockRejectedValue(new Error('product not found: 999999'))
    renderDetail('/products/999999')
    await waitFor(() => expect(screen.getByText(/商品不存在|not found/i)).toBeInTheDocument())
  })
})
