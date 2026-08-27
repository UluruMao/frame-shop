import { describe, it, expect, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import RequireAdmin from '@/router/RequireAdmin'
import { useAuthStore } from '@/store/useAuthStore'

/**
 * RequireAdmin route guard (Task 8):
 *  - no token → redirect to /admin/login
 *  - token but role !== ADMIN → redirect to /products
 *
 * Uses the real Zustand store (mutated via setState) rather than module mocks,
 * so both branches share one import of RequireAdmin.
 */
function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/products" element={<RequireAdmin><div>secret</div></RequireAdmin>} />
        <Route path="/products" element={<div>products</div>} />
        <Route path="/admin/login" element={<div>admin-login</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('RequireAdmin', () => {
  afterEach(() => useAuthStore.setState({ token: null, user: null }))

  it('redirects non-admins to /products', () => {
    useAuthStore.setState({ token: 't', user: { userId: 1, username: 'u', role: 'USER' } })
    renderAt('/admin/products')
    expect(screen.queryByText('secret')).toBeNull()
    expect(screen.getByText('products')).toBeInTheDocument()
  })

  it('redirects unauthenticated users to /admin/login', () => {
    useAuthStore.setState({ token: null, user: null })
    renderAt('/admin/products')
    expect(screen.queryByText('secret')).toBeNull()
    expect(screen.getByText('admin-login')).toBeInTheDocument()
  })
})
