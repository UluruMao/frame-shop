import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { CartProvider } from '@/store/CartContext'
import Products from '@/pages/Products'
import type { Product, PageResult } from '@/api/types'

/**
 * Products page — countdown + live stock bar tests.
 *
 * Flash-sale products with a future `flashSaleAt` show a countdown timer.
 * Once `flashSaleAt` has passed, the page polls the product detail endpoint
 * every 4s and renders a recharts BarChart of the remaining stock.
 */

vi.mock('@/api/product', () => ({
  pageProducts: vi.fn(),
  getProduct: vi.fn(),
}))

import { pageProducts, getProduct } from '@/api/product'

function product(overrides: Partial<Product> = {}): Product {
  return {
    id: 1,
    name: 'Flash Widget',
    priceInCents: 1000,
    stock: 100,
    isFlashSale: true,
    ...overrides,
  }
}

function renderProducts() {
  return render(
    <MemoryRouter>
      <CartProvider>
        <Products />
      </CartProvider>
    </MemoryRouter>,
  )
}

function mockPage(records: Product[]): PageResult<Product> {
  return { total: records.length, current: 1, size: 50, records }
}

describe('Products — flash sale countdown + stock bar', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.clearAllMocks()
    localStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
    localStorage.clear()
  })

  it('shows a countdown timer when flashSaleAt is in the future', async () => {
    const future = new Date(Date.now() + 3_600_000).toISOString() // +1h
    vi.mocked(pageProducts).mockResolvedValue(mockPage([product({ flashSaleAt: future, stock: 100 })]))

    renderProducts()

    await waitFor(() => {
      expect(screen.getByText('Flash Widget')).toBeInTheDocument()
    })
    // Countdown label is visible
    expect(screen.getByText('Sale opens in')).toBeInTheDocument()
    // The countdown shows a time (HH:MM:SS format)
    expect(screen.getByText(/^\d{2}:\d{2}:\d{2}$/)).toBeInTheDocument()
  })

  it('shows a live stock bar when flashSaleAt has passed (sale is live)', async () => {
    const past = new Date(Date.now() - 60_000).toISOString() // 1 min ago
    vi.mocked(pageProducts).mockResolvedValue(mockPage([product({ flashSaleAt: past, stock: 50 })]))
    vi.mocked(getProduct).mockResolvedValue(product({ flashSaleAt: past, stock: 30 }))

    renderProducts()

    await waitFor(() => {
      expect(screen.getByText('Flash Widget')).toBeInTheDocument()
    })
    // Sale is live → "Live stock" label appears
    await waitFor(() => {
      expect(screen.getByText('Live stock')).toBeInTheDocument()
    })
    // getProduct is polled for live stock
    expect(vi.mocked(getProduct)).toHaveBeenCalledWith(1)
    // Stock ratio shown: 30 / 50
    expect(screen.getByText('30 / 50')).toBeInTheDocument()
  })

  it('polls product detail every 4 seconds for live stock', async () => {
    const past = new Date(Date.now() - 60_000).toISOString()
    vi.mocked(pageProducts).mockResolvedValue(mockPage([product({ flashSaleAt: past, stock: 50 })]))
    vi.mocked(getProduct).mockResolvedValue(product({ flashSaleAt: past, stock: 20 }))

    renderProducts()

    await waitFor(() => {
      expect(screen.getByText('Live stock')).toBeInTheDocument()
    })

    // Initial poll on mount
    expect(vi.mocked(getProduct)).toHaveBeenCalledTimes(1)

    // Advance 4s → second poll
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4100)
    })
    expect(vi.mocked(getProduct)).toHaveBeenCalledTimes(2)

    // Advance another 4s → third poll
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4100)
    })
    expect(vi.mocked(getProduct)).toHaveBeenCalledTimes(3)
  })

  it('does not show countdown or stock bar for non-flash-sale products', async () => {
    vi.mocked(pageProducts).mockResolvedValue(
      mockPage([product({ isFlashSale: false, flashSaleAt: undefined })]),
    )

    renderProducts()

    await waitFor(() => {
      expect(screen.getByText('Flash Widget')).toBeInTheDocument()
    })
    expect(screen.queryByText('Sale opens in')).not.toBeInTheDocument()
    expect(screen.queryByText('Live stock')).not.toBeInTheDocument()
  })
})
