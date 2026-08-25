import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts'
import { TrendingUp, Package, DollarSign, Activity } from 'lucide-react'
import Card from '@/components/ui/Card'

// Static demo data — replace with a real API call when business data exists.
const salesData = [
  { month: 'Jan', sales: 400 },
  { month: 'Feb', sales: 300 },
  { month: 'Mar', sales: 520 },
  { month: 'Apr', sales: 460 },
  { month: 'May', sales: 610 },
  { month: 'Jun', sales: 550 },
]

const stats = [
  { label: 'Total sales', value: '$12,480', icon: DollarSign },
  { label: 'Orders', value: '284', icon: TrendingUp },
  { label: 'Products', value: '18', icon: Package },
  { label: 'Active', value: '7', icon: Activity },
]

export default function Dashboard() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold text-slate-800">Dashboard</h1>
        <p className="text-sm text-slate-500">Demo: lucide icons + recharts chart.</p>
      </div>

      {/* Stat tiles */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {stats.map(({ label, value, icon: Icon }) => (
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

      {/* Chart */}
      <Card className="p-4">
        <h2 className="text-sm font-medium text-slate-700 mb-3">Monthly sales</h2>
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={salesData}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
              <XAxis dataKey="month" stroke="#64748b" fontSize={12} />
              <YAxis stroke="#64748b" fontSize={12} />
              <Tooltip />
              <Bar dataKey="sales" fill="#2563eb" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </Card>
    </div>
  )
}
