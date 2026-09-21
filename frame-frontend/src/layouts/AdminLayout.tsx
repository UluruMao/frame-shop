import { NavLink, Outlet, useNavigate, Link } from 'react-router-dom'
import { LayoutDashboard, Package, ClipboardList, MessageCircle, LogOut, Boxes } from 'lucide-react'
import { useAuthStore } from '@/store/useAuthStore'

const NAV = [
  { to: '/admin/dashboard', label: '控制台', icon: LayoutDashboard },
  { to: '/admin/products', label: '商品', icon: Package },
  { to: '/admin/orders', label: '订单', icon: ClipboardList },
  { to: '/admin/chat', label: '客服', icon: MessageCircle },
]

export default function AdminLayout() {
  const navigate = useNavigate()
  const clear = useAuthStore((s) => s.clear)
  const user = useAuthStore((s) => s.user)
  function handleLogout() { clear(); navigate('/admin/login', { replace: true }) }
  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-white border-b border-slate-200">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
          <Link to="/admin/dashboard" className="flex items-center gap-2 font-semibold text-slate-800 hover:text-brand-600 transition-colors">
            <Boxes className="w-5 h-5 text-brand-600" /><span>秒杀商城 · 管理后台</span>
          </Link>
          <nav className="flex items-center gap-1">
            {NAV.map(({ to, label, icon: Icon }) => (
              <NavLink key={to} to={to} className={({ isActive }) => [
                'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-colors',
                isActive ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-100',
              ].join(' ')}>
                <Icon className="w-4 h-4" />{label}
              </NavLink>
            ))}
          </nav>
          <div className="flex items-center gap-3">
            <span className="text-sm text-slate-500">{user?.username ?? '管理员'}</span>
            <button onClick={handleLogout} className="flex items-center gap-1.5 text-sm text-slate-600 hover:text-slate-900">
              <LogOut className="w-4 h-4" />登出
            </button>
          </div>
        </div>
      </header>
      <main className="flex-1 max-w-6xl w-full mx-auto px-4 py-6"><Outlet /></main>
    </div>
  )
}
