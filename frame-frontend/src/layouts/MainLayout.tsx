import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { LayoutDashboard, Package, LogOut, Boxes, ShoppingCart, ClipboardList, Shield } from 'lucide-react'
import { useAuthStore, useIsAdmin } from '@/store/useAuthStore'

const navItems = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/products', label: 'Products', icon: Package },
  { to: '/checkout', label: 'Checkout', icon: ShoppingCart },
  { to: '/orders', label: 'Orders', icon: ClipboardList },
]

export default function MainLayout() {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const clear = useAuthStore((s) => s.clear)
  const isAdmin = useIsAdmin()

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
            <span>frame-frontend</span>
          </div>
          <nav className="flex items-center gap-1">
            {navItems.map(({ to, label, icon: Icon }) => (
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
            {isAdmin && (
              <NavLink
                to="/admin"
                className={({ isActive }) =>
                  [
                    'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-colors',
                    isActive
                      ? 'bg-brand-50 text-brand-700'
                      : 'text-slate-600 hover:bg-slate-100',
                  ].join(' ')
                }
              >
                <Shield className="w-4 h-4" />
                Admin
              </NavLink>
            )}
          </nav>
          <div className="flex items-center gap-3">
            <span className="text-sm text-slate-500">
              {user?.username ?? 'guest'}
            </span>
            <button
              onClick={handleLogout}
              className="flex items-center gap-1.5 text-sm text-slate-600 hover:text-slate-900"
            >
              <LogOut className="w-4 h-4" />
              Logout
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
