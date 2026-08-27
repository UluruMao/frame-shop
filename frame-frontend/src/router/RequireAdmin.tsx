import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuthStore } from '@/store/useAuthStore'

/**
 * Wraps the /admin/* route tree. If there's no token, redirect to /admin/login
 * and remember where the admin was trying to go. If the logged-in user is not
 * an ADMIN, send them to the storefront (they have no admin surfaces).
 */
export default function RequireAdmin({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token)
  const role = useAuthStore((s) => s.user?.role)
  const loc = useLocation()
  if (!token) return <Navigate to="/admin/login" state={{ from: loc }} replace />
  if (role !== 'ADMIN') return <Navigate to="/products" replace />
  return <>{children}</>
}
