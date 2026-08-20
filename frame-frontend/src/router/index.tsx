import { createBrowserRouter, type RouteObject } from 'react-router-dom'
import MainLayout from '@/layouts/MainLayout'
import RequireAuth from './RequireAuth'
import { CartProvider } from '@/store/CartContext'
import Login from '@/pages/Login'
import Register from '@/pages/Register'
import ForgotPassword from '@/pages/ForgotPassword'
import Dashboard from '@/pages/Dashboard'
import Products from '@/pages/Products'
import Checkout from '@/pages/Checkout'
import Orders from '@/pages/Orders'
import Admin from '@/pages/Admin'
import Profile from '@/pages/Profile'

const routes: RouteObject[] = [
  { path: '/login', element: <Login /> },
  { path: '/register', element: <Register /> },
  { path: '/forgot-password', element: <ForgotPassword /> },
  // Public catalog browse (UC000: guests may browse/search/sort). Add-to-cart stays protected.
  { path: '/products', element: <Products /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        {/* CartProvider wraps the protected layout so the cart survives page navigation. */}
        <CartProvider>
          <MainLayout />
        </CartProvider>
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Dashboard /> },
      { path: 'dashboard', element: <Dashboard /> },
      { path: 'checkout', element: <Checkout /> },
      { path: 'orders', element: <Orders /> },
      { path: 'profile', element: <Profile /> },
      { path: 'admin', element: <Admin /> },
    ],
  },
]

export const router = createBrowserRouter(routes)
