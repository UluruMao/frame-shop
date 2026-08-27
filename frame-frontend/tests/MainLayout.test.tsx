import { describe, it, expect, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import MainLayout from '@/layouts/MainLayout'
import { useAuthStore } from '@/store/useAuthStore'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="*" element={<MainLayout />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('MainLayout nav', () => {
  afterEach(() => useAuthStore.setState({ token: null, user: null }))

  it('shows only 商品 for a guest', () => {
    renderAt('/')
    expect(screen.getByText('商品')).toBeInTheDocument()
    expect(screen.queryByText('订单')).not.toBeInTheDocument()
    expect(screen.queryByText('控制台')).not.toBeInTheDocument()
  })

  it('shows commerce menus for a logged-in non-admin', () => {
    useAuthStore.setState({ token: 't', user: { userId: 1, username: 'u', role: 'USER' } })
    renderAt('/')
    expect(screen.getByText('商品')).toBeInTheDocument()
    expect(screen.getByText('订单')).toBeInTheDocument()
    expect(screen.getByText('金币')).toBeInTheDocument()
    expect(screen.queryByText('管理后台')).not.toBeInTheDocument()
  })

  it('shows 商品 + 管理后台 for an admin (admins use AdminLayout, not MainLayout, for their work)', () => {
    useAuthStore.setState({ token: 't', user: { userId: 1, username: 'a', role: 'ADMIN' } })
    renderAt('/')
    expect(screen.getByText('商品')).toBeInTheDocument()
    expect(screen.getByText('管理后台')).toBeInTheDocument()
    // Admins no longer get the commerce menus or a Dashboard link here — those
    // live in AdminLayout under /admin/*.
    expect(screen.queryByText('订单')).not.toBeInTheDocument()
    expect(screen.queryByText('控制台')).not.toBeInTheDocument()
  })
})
