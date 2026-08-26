import { useEffect, useState } from 'react'
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts'
import { TrendingUp, Package, DollarSign, RotateCcw, Star } from 'lucide-react'
import Card from '@/components/ui/Card'
import Skeleton from '@/components/ui/Skeleton'
import EmptyState from '@/components/ui/EmptyState'
import { getAdminStats, type DashboardStats } from '@/api/stats'
import { formatPrice } from '@/lib/format'

export default function Dashboard() {
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    getAdminStats()
      .then(setStats)
      .catch((e) => setError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [])

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

      {/* Monthly revenue chart */}
      <Card className="p-4">
        <h2 className="text-sm font-medium text-slate-700 mb-3">月度营收</h2>
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={stats.monthlyRevenue}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
              <XAxis dataKey="month" stroke="#64748b" fontSize={12} />
              <YAxis stroke="#64748b" fontSize={12} tickFormatter={(v) => `¥${(v / 100).toFixed(0)}`} />
              <Tooltip formatter={(v: number) => formatPrice(v)} />
              <Bar dataKey="revenueCents" fill="#2563eb" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </Card>
    </div>
  )
}
