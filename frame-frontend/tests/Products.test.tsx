import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import Products from '@/pages/Products'

vi.mock('@/api/product', () => ({
  pageProducts: vi.fn(),
  listCategories: vi.fn(),
  getProduct: vi.fn(),
}))

import { pageProducts, listCategories } from '@/api/product'

const product = (overrides: Partial<any> = {}) => ({
  id: 1,
  name: 'Red Widget',
  priceInCents: 1000,
  stock: 5,
  category: 'toys',
  isFlashSale: false,
  sold: 4,
  avgRating: 4.0,
  ...overrides,
})

const page = (records: any[]) => ({ total: records.length, current: 1, size: 12, records })

beforeEach(() => {
  vi.clearAllMocks()
  ;(listCategories as any).mockResolvedValue(['toys', 'tools'])
  ;(pageProducts as any).mockResolvedValue(page([product()]))
})

const renderProducts = () =>
  render(
    <MemoryRouter>
      <Products />
    </MemoryRouter>,
  )

describe('Products — catalog (UC000)', () => {
  it('renders product cards and links to detail', async () => {
    renderProducts()
    await waitFor(() => expect(screen.getByText('Red Widget')).toBeInTheDocument())
    expect(screen.getByText(/10\.00|10,/)).toBeInTheDocument() // price display
    expect(screen.getByRole('link', { name: /查看详情|Red Widget/i })).toHaveAttribute('href', '/products/1')
  })

  it('submits a keyword search', async () => {
    renderProducts()
    await waitFor(() => expect(pageProducts).toHaveBeenCalled())
    const input = screen.getByPlaceholderText(/搜索|keyword|搜索商品/i)
    fireEvent.change(input, { target: { value: 'red' } })
    fireEvent.click(screen.getByRole('button', { name: /搜索|search/i }))
    await waitFor(() =>
      expect(pageProducts).toHaveBeenCalledWith(expect.objectContaining({ keyword: 'red' })),
    )
  })

  it('changes sort field + order via the sort control', async () => {
    renderProducts()
    await waitFor(() => expect(pageProducts).toHaveBeenCalled())
    fireEvent.change(screen.getByLabelText(/排序|sort/i), { target: { value: 'price|asc' } })
    await waitFor(() =>
      expect(pageProducts).toHaveBeenCalledWith(expect.objectContaining({ sortField: 'price' })),
    )
  })

  it('sort dropdown exposes all 6 sort options incl sales|asc + rating|asc', async () => {
    renderProducts()
    await waitFor(() => expect(pageProducts).toHaveBeenCalled())
    const select = screen.getByLabelText(/排序|sort/i) as HTMLSelectElement
    const values = Array.from(select.options).map((o) => o.value)
    expect(values).toEqual(
      expect.arrayContaining([
        '',
        'price|asc',
        'price|desc',
        'sales|desc',
        'sales|asc',
        'rating|desc',
        'rating|asc',
      ]),
    )
  })

  it('filters by category', async () => {
    renderProducts()
    await waitFor(() => expect(pageProducts).toHaveBeenCalled())
    fireEvent.change(screen.getByLabelText(/分类|category/i), { target: { value: 'toys' } })
    await waitFor(() =>
      expect(pageProducts).toHaveBeenCalledWith(expect.objectContaining({ category: 'toys' })),
    )
  })

  it('shows 暂无数据 when results are empty', async () => {
    ;(pageProducts as any).mockResolvedValue(page([]))
    renderProducts()
    await waitFor(() => expect(screen.getByText('暂无数据')).toBeInTheDocument())
  })

  it('renders the Pagination kit (下一页) instead of raw buttons when total > size', async () => {
    // Multi-page mock: 20 records, size 12 → 2 pages, so the kit renders.
    ;(pageProducts as any).mockResolvedValue({
      total: 20,
      current: 1,
      size: 12,
      records: [product({ id: 1 }), product({ id: 2 })],
    })
    renderProducts()
    await waitFor(() => expect(screen.getByText(/共 2 页/)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '下一页' })).toBeInTheDocument()
  })
})
