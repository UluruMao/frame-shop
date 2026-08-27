import { useEffect, useState } from 'react'
import {
  BarChart, Bar,
  LineChart, Line,
  PieChart, Pie, Cell,
  XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, Legend,
} from 'recharts'
import { TrendingUp, Package, DollarSign, RotateCcw, Star } from 'lucide-react'
import Card from '@/components/ui/Card'
import Skeleton from '@/components/ui/Skeleton'
import EmptyState from '@/components/ui/EmptyState'
import Select from '@/components/ui/Select'
import { getAdminStats, type DashboardStats } from '@/api/stats'
import { formatPrice } from '@/lib/format'

const RANGE_OPTIONS = [
  { value: '1d', label: '近1天' },
  { value: '7d', label: '近7天' },
  { value: '30d', label: '近30天' },
  { value: '90d', label: '近90天' },
  { value: '365d', label: '近1年' },
  { value: '1095d', label: '近3年' },
  { value: 'custom', label: '自定义' },
]

// Validated categorical palette (dataviz skill, 3-slot all-pairs pass on light).
// Pie is an all-pairs chart: cap at 3 categorical slots, fold the rest to "Other" gray.
const PIE_COLORS = ['#2a78d6', '#eb6834', '#1baf7a']
const PIE_OTHER_COLOR = '#9ca3af'
const SERIES_BLUE = '#2a78d6'

export default function Dashboard() {
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [range, setRange] = useState('30d')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  useEffect(() => {
    setLoading(true)
    getAdminStats(
      range,
      range === 'custom' ? from || undefined : undefined,
      range === 'custom' ? to || undefined : undefined,
    )
      .then(setStats)
      .catch((e) => setError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [range, from, to])

  if (loading) {
    return (
      <div className="space-y-6">
        <h1 className="text-xl font-semibold text-slate-800">控制台</h1>
        <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
          {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-20" />)}
        </div>
        <Skeleton className="h-64" />
      </div>
    )
  }

  if (error || !stats) {
    return (
      <div className="space-y-6">
        <h1 className="text-xl font-semibold text-slate-800">控制台</h1>
        <EmptyState title={error ?? '暂无数据'} />
      </div>
    )
  }

  const refundRate = stats.totalOrders > 0 ? (stats.refundCount / stats.totalOrders) * 100 : 0
  const tiles = [
    { label: '总营收', value: formatPrice(stats.totalRevenueCents), icon: DollarSign },
    { label: '总订单', value: String(stats.totalOrders), icon: TrendingUp },
    { label: '商品数', value: String(stats.totalProducts), icon: Package },
    { label: '退款率', value: `${refundRate.toFixed(2)}%`, icon: RotateCcw },
    { label: '平均评分', value: stats.avgRating.toFixed(1), icon: Star },
  ]

  // Pie: top 3 by units, rest folded to "Other" (all-pairs CVD-safe cap).
  const sortedShare = [...stats.salesShareByProduct].sort((a, b) => b.units - a.units)
  const top = sortedShare.slice(0, 3)
  const rest = sortedShare.slice(3)
  const pieData = [
    ...top.map((p) => ({ name: p.name, units: p.units })),
    ...(rest.length > 0
      ? [{ name: '其他', units: rest.reduce((s, p) => s + p.units, 0) }]
      : []),
  ]

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold text-slate-800">控制台</h1>

      {/* Stat tiles (5-up) */}
      <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
        {tiles.map(({ label, value, icon: Icon }) => (
          <Card key={label} className="p-4 flex items-center gap-3">
            <div className="w-10 h-10 rounded-md bg-brand-50 flex items-center justify-center">
              <Icon className="w-5 h-5 text-brand-600" />
            </div>
            <div>
              <div className="text-lg font-semibold text-slate-800">{value}</div>
              <div className="text-xs text-slate-500">{label}</div>
            </div>
          </Card>
        ))}
      </div>

      {/* Filter bar */}
      <Card className="p-4">
        <div className="flex flex-wrap items-end gap-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="range-select" className="text-xs text-slate-500">时间范围</label>
            <Select id="range-select" aria-label="时间范围" value={range} onChange={(e) => setRange(e.target.value)} className="w-40">
              {RANGE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </Select>
          </div>
          {range === 'custom' && (
            <>
              <div className="flex flex-col gap-1">
                <label htmlFor="from-date" className="text-xs text-slate-500">起始日期</label>
                <input
                  id="from-date"
                  type="date"
                  value={from}
                  onChange={(e) => setFrom(e.target.value)}
                  className="px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500 bg-white"
                />
              </div>
              <div className="flex flex-col gap-1">
                <label htmlFor="to-date" className="text-xs text-slate-500">结束日期</label>
                <input
                  id="to-date"
                  type="date"
                  value={to}
                  onChange={(e) => setTo(e.target.value)}
                  className="px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500 bg-white"
                />
              </div>
            </>
          )}
        </div>
      </Card>

      {/* Bar — units by product (single series, magnitude) */}
      <Card className="p-4">
        <h2 className="text-sm font-medium text-slate-700 mb-3">商品销量</h2>
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={stats.unitsByProduct}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
              <XAxis dataKey="name" stroke="#64748b" fontSize={12} />
              <YAxis stroke="#64748b" fontSize={12} allowDecimals={false} />
              <Tooltip />
              <Bar dataKey="units" fill={SERIES_BLUE} radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </Card>

      {/* Line — revenue trend (single series) */}
      <Card className="p-4">
        <h2 className="text-sm font-medium text-slate-700 mb-3">营收趋势</h2>
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={stats.revenueTrend}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
              <XAxis dataKey="bucket" stroke="#64748b" fontSize={12} />
              <YAxis stroke="#64748b" fontSize={12} tickFormatter={(v) => `¥${(v / 100).toFixed(0)}`} />
              <Tooltip formatter={(v: number) => formatPrice(v)} />
              <Line type="monotone" dataKey="cents" stroke={SERIES_BLUE} strokeWidth={2} dot={{ r: 4, fill: SERIES_BLUE }} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </Card>

      {/* Pie — sales share by product (categorical identity, top 3 + Other) */}
      <Card className="p-4">
        <h2 className="text-sm font-medium text-slate-700 mb-3">销量占比</h2>
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie data={pieData} dataKey="units" nameKey="name" cx="50%" cy="50%" outerRadius={80} label>
                {pieData.map((entry, i) => (
                  <Cell key={entry.name} fill={i < PIE_COLORS.length ? PIE_COLORS[i] : PIE_OTHER_COLOR} />
                ))}
              </Pie>
              <Tooltip />
              <Legend />
            </PieChart>
          </ResponsiveContainer>
        </div>
      </Card>
    </div>
  )
}
