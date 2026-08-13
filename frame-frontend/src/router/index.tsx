import { createBrowserRouter, type RouteObject } from 'react-router-dom'
import MainLayout from '@/layouts/MainLayout'
import RequireAuth from './RequireAuth'
import Login from '@/pages/Login'
import Dashboard from '@/pages/Dashboard'
import Products from '@/pages/Products'

const routes: RouteObject[] = [
  { path: '/login', element: <Login /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <MainLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Dashboard /> },
      { path: 'dashboard', element: <Dashboard /> },
      { path: 'products', element: <Products /> },
    ],
  },
]

export const router = createBrowserRouter(routes)
