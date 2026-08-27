import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { Package, LogOut, Boxes, ShoppingCart, ClipboardList, Shield, User, MessageCircle, Coins } from 'lucide-react'
import { useAuthStore } from '@/store/useAuthStore'

type NavItem = { to: string; label: string; icon: typeof Package }

/** Role-aware nav: guest=browse only; user=commerce; admin=a single link into
 *  the /admin/* section (admins use AdminLayout, not MainLayout, for their work). */
function navFor(role: string | undefined): NavItem[] {
  if (role === 'ADMIN') {
    return [
      { to: '/products', label: '商品', icon: Package },
      { to: '/admin', label: '管理后台', icon: Shield },
    ]
  }
  if (role === 'USER') {
    return [
      { to: '/products', label: '商品', icon: Package },
      { to: '/checkout', label: '结算', icon: ShoppingCart },
      { to: '/orders', label: '订单', icon: ClipboardList },
      { to: '/coins', label: '金币', icon: Coins },
      { to: '/chat', label: '客服', icon: MessageCircle },
      { to: '/profile', label: '个人中心', icon: User },
    ]
  }
  // Guest: catalog only (UC000).
  return [{ to: '/products', label: '商品', icon: Package }]
}

export default function MainLayout() {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const clear = useAuthStore((s) => s.clear)

  function handleLogout() {
    clear()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-white border-b border-slate-200">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-2 font-semibold text-slate-800">
            <Boxes className="w-5 h-5 text-brand-600" />
            <span>秒杀商城</span>
          </div>
          <nav className="flex items-center gap-1">
            {navFor(user?.role).map(({ to, label, icon: Icon }) => (
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  [
                    'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-colors',
                    isActive
                      ? 'bg-brand-50 text-brand-700'
                      : 'text-slate-600 hover:bg-slate-100',
                  ].join(' ')
                }
              >
                <Icon className="w-4 h-4" />
                {label}
              </NavLink>
            ))}
          </nav>
          <div className="flex items-center gap-3">
            <span className="text-sm text-slate-500">
              {user?.username ?? '访客'}
            </span>
            <button
              onClick={handleLogout}
              className="flex items-center gap-1.5 text-sm text-slate-600 hover:text-slate-900"
            >
              <LogOut className="w-4 h-4" />
              登出
            </button>
          </div>
        </div>
      </header>

      <main className="flex-1 max-w-6xl w-full mx-auto px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
