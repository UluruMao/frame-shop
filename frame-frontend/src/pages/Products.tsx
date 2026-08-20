import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Search } from 'lucide-react'
import { pageProducts, listCategories } from '@/api/product'
import type { ProductListItem, PageQuery } from '@/api/types'

/** Format integer cents as a dollar string: 1299 → "$12.99". */
export function formatPrice(priceInCents: number): string {
  return `$${(priceInCents / 100).toFixed(2)}`
}

export default function Products() {
  const [items, setItems] = useState<ProductListItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [categories, setCategories] = useState<string[]>([])
  const [query, setQuery] = useState<PageQuery>({ current: 1, size: 12 })
  const [keywordInput, setKeywordInput] = useState('')

  useEffect(() => {
    listCategories().then(setCategories).catch(() => {})
  }, [])

  useEffect(() => {
    setLoading(true)
    pageProducts(query)
      .then((r) => {
        setItems(r.records)
        setTotal(r.total)
      })
      .finally(() => setLoading(false))
  }, [query])

  const onSearch = () => setQuery((q) => ({ ...q, keyword: keywordInput || undefined, current: 1 }))
  const onSort = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const [field, order] = e.target.value.split('|')
    setQuery((q) => ({ ...q, sortField: (field || undefined) as PageQuery['sortField'], sortOrder: (order as 'asc' | 'desc') || 'desc', current: 1 }))
  }
  const onCategory = (e: React.ChangeEvent<HTMLSelectElement>) =>
    setQuery((q) => ({ ...q, category: e.target.value || undefined, current: 1 }))

  return (
    <div className="mx-auto max-w-6xl p-4">
      <h1 className="mb-4 text-2xl font-bold">商品列表 ({total})</h1>
      <div className="mb-4 flex flex-wrap gap-2">
        <input
          className="border p-2 rounded"
          placeholder="搜索商品名称，多关键词用逗号或空格"
          value={keywordInput}
          onChange={(e) => setKeywordInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && onSearch()}
        />
        <button className="rounded bg-brand-600 px-3 py-2 text-white" onClick={onSearch}>
          <Search className="inline h-4 w-4" /> 搜索
        </button>
        <select aria-label="分类" className="border p-2 rounded" onChange={onCategory} value={query.category ?? ''}>
          <option value="">全部分类</option>
          {categories.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <select aria-label="排序" className="border p-2 rounded" onChange={onSort}>
          <option value="">默认</option>
          <option value="price|asc">价格升序</option>
          <option value="price|desc">价格降序</option>
          <option value="sales|desc">销量降序</option>
          <option value="rating|desc">评价降序</option>
        </select>
      </div>

      {loading ? (
        <p>加载中...</p>
      ) : items.length === 0 ? (
        <p className="text-gray-500">暂无数据</p>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {items.map((p) => (
            <Link to={`/products/${p.id}`} key={p.id} className="block rounded border p-3 hover:shadow">
              {p.imageUrl && <img src={p.imageUrl} alt={p.name} loading="lazy" className="mb-2 h-32 w-full object-cover" />}
              <div className="font-medium">{p.name}</div>
              <div className="text-brand-600">¥{(p.priceInCents / 100).toFixed(2)}</div>
              <div className="text-sm text-gray-500">已售 {p.sold} · 评分 {p.avgRating.toFixed(1)}</div>
            </Link>
          ))}
        </div>
      )}

      {total > (query.size ?? 12) && (
        <div className="mt-4 flex gap-2">
          <button disabled={(query.current ?? 1) <= 1} onClick={() => setQuery((q) => ({ ...q, current: (q.current ?? 1) - 1 }))} className="border px-3 py-1 rounded">上一页</button>
          <button onClick={() => setQuery((q) => ({ ...q, current: (q.current ?? 1) + 1 }))} className="border px-3 py-1 rounded">下一页</button>
        </div>
      )}
    </div>
  )
}
