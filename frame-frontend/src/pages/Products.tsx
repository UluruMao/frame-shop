import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Search } from 'lucide-react'
import { pageProducts, listCategories } from '@/api/product'
import type { ProductListItem, PageQuery } from '@/api/types'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'
import Select from '@/components/ui/Select'
import Card from '@/components/ui/Card'
import EmptyState from '@/components/ui/EmptyState'
import Pagination from '@/components/ui/Pagination'
import Spinner from '@/components/ui/Spinner'
import { formatPrice } from '@/lib/format'

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
      <h1 className="mb-4 text-2xl font-bold">商品列表</h1>
      <div className="mb-4 flex flex-wrap gap-2">
        <Input
          className="w-auto"
          placeholder="搜索商品名称，多关键词用逗号或空格"
          value={keywordInput}
          onChange={(e) => setKeywordInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && onSearch()}
        />
        <Button size="sm" icon={<Search className="h-4 w-4" />} onClick={onSearch}>
          搜索
        </Button>
        <Select aria-label="分类" className="w-auto" onChange={onCategory} value={query.category ?? ''}>
          <option value="">全部分类</option>
          {categories.map((c) => <option key={c} value={c}>{c}</option>)}
        </Select>
        <Select aria-label="排序" className="w-auto" onChange={onSort}>
          <option value="">默认</option>
          <option value="price|asc">价格升序</option>
          <option value="price|desc">价格降序</option>
          <option value="sales|desc">销量降序</option>
          <option value="sales|asc">销量升序</option>
          <option value="rating|desc">评价降序</option>
          <option value="rating|asc">评价升序</option>
        </Select>
      </div>

      {loading ? (
        <div className="flex flex-col items-center gap-2 py-10">
          <Spinner />
          <p>加载中...</p>
        </div>
      ) : items.length === 0 ? (
        <EmptyState title="暂无数据" />
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {items.map((p) => (
            <Link to={`/products/${p.id}`} key={p.id}>
              <Card className="p-3 transition-shadow hover:shadow">
                {p.imageUrl && <img src={p.imageUrl} alt={p.name} loading="lazy" className="mb-2 h-32 w-full object-cover" />}
                <div className="font-medium">{p.name}</div>
                <div className="text-brand-600">{formatPrice(p.priceInCents)}</div>
                <div className="text-sm text-slate-500">已售 {p.sold} · 评分 {p.avgRating.toFixed(1)}</div>
              </Card>
            </Link>
          ))}
        </div>
      )}

      <Pagination
        current={query.current ?? 1}
        size={query.size ?? 12}
        total={total}
        onChange={(page) => setQuery((q) => ({ ...q, current: page }))}
      />
    </div>
  )
}
