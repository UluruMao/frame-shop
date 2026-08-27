import { createBrowserRouter, Navigate, type RouteObject } from 'react-router-dom'
import MainLayout from '@/layouts/MainLayout'
import RequireAuth from './RequireAuth'
import RequireAdmin from './RequireAdmin'
import { CartProvider } from '@/store/CartContext'
import { useAuthStore } from '@/store/useAuthStore'
import Login from '@/pages/Login'
import Register from '@/pages/Register'
import ForgotPassword from '@/pages/ForgotPassword'
import AdminLogin from '@/pages/AdminLogin'
import Dashboard from '@/pages/Dashboard'
import Products from '@/pages/Products'
import ProductDetailPage from '@/pages/ProductDetailPage'
import Checkout from '@/pages/Checkout'
import Orders from '@/pages/Orders'
import Coins from '@/pages/Coins'
import Profile from '@/pages/Profile'
import Chat from '@/pages/Chat'
import AdminLayout from '@/layouts/AdminLayout'
import AdminDashboard from '@/pages/admin/AdminDashboard'
import AdminProducts from '@/pages/admin/AdminProducts'
import AdminOrders from '@/pages/admin/AdminOrders'
import AdminChat from '@/pages/admin/AdminChat'

/** Index route: guests (no token) and logged-in non-admins land on Products;
 *  logged-in admins land on the admin dashboard (under /admin/*). */
function IndexRoute() {
  const token = useAuthStore((s) => s.token)
  const role = useAuthStore((s) => s.user?.role)
  // Guests (no token) → Products; logged-in non-admins → Products (Dashboard
  // is @PreAuthorize("hasRole('ADMIN')") and would 403 them); admins → /admin/dashboard.
  const dest = token && role === 'ADMIN' ? '/admin/dashboard' : '/products'
  return <Navigate to={dest} replace />
}

const routes: RouteObject[] = [
  { path: '/login', element: <Login /> },
  { path: '/register', element: <Register /> },
  { path: '/forgot-password', element: <ForgotPassword /> },
  // Admin login is standalone (no MainLayout shell) — mirrors /login.
  { path: '/admin/login', element: <AdminLogin /> },
  // Shared app shell: CartProvider + MainLayout are PUBLIC so guests can browse the
  // catalog (UC000) with the nav/header/cart present, and ProductDetailPage's useCart()
  // never crashes. Only the specific protected child routes are gated by RequireAuth
  // (wrapped per-element below) — checkout/orders/profile still require login.
  {
    path: '/',
    element: (
      <CartProvider>
        <MainLayout />
      </CartProvider>
    ),
    children: [
      { index: true, element: <IndexRoute /> },
      { path: 'dashboard', element: <RequireAuth><Dashboard /></RequireAuth> },
      // Public catalog browse (UC000: guests may browse/search/sort). Add-to-cart stays protected.
      { path: 'products', element: <Products /> },
      { path: 'products/:id', element: <ProductDetailPage /> },
      { path: 'checkout', element: <RequireAuth><Checkout /></RequireAuth> },
      { path: 'orders', element: <RequireAuth><Orders /></RequireAuth> },
      { path: 'coins', element: <RequireAuth><Coins /></RequireAuth> },
      { path: 'profile', element: <RequireAuth><Profile /></RequireAuth> },
      { path: 'chat', element: <RequireAuth><Chat /></RequireAuth> },
    ],
  },
  // Admin route tree: RequireAdmin gates the whole subtree (no token → /admin/login;
  // non-admin → /products). AdminLayout provides the admin nav shell + <Outlet/>.
  {
    path: '/admin',
    element: <RequireAdmin><AdminLayout /></RequireAdmin>,
    children: [
      { index: true, element: <Navigate to="/admin/dashboard" replace /> },
      { path: 'dashboard', element: <AdminDashboard /> },
      { path: 'products', element: <AdminProducts /> },
      { path: 'orders', element: <AdminOrders /> },
      { path: 'chat', element: <AdminChat /> },
    ],
  },
]

export const router = createBrowserRouter(routes)
