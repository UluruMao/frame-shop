import { createBrowserRouter, type RouteObject } from 'react-router-dom'
import MainLayout from '@/layouts/MainLayout'
import RequireAuth from './RequireAuth'
import { CartProvider } from '@/store/CartContext'
import Login from '@/pages/Login'
import Register from '@/pages/Register'
import Dashboard from '@/pages/Dashboard'
import Products from '@/pages/Products'
import Checkout from '@/pages/Checkout'
import Orders from '@/pages/Orders'
import Admin from '@/pages/Admin'

const routes: RouteObject[] = [
  { path: '/login', element: <Login /> },
  { path: '/register', element: <Register /> },
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
      { path: 'products', element: <Products /> },
      { path: 'checkout', element: <Checkout /> },
      { path: 'orders', element: <Orders /> },
      { path: 'admin', element: <Admin /> },
    ],
  },
]

export const router = createBrowserRouter(routes)
