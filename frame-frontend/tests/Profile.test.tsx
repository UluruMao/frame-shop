import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import Profile from '@/pages/Profile'

// vi.mock factories are hoisted above all imports by vitest, so any mock fn
// that the test must later assert on must itself be created via vi.hoisted()
// to be in scope when the factory runs.
const { deleteAccount } = vi.hoisted(() => ({
  deleteAccount: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/api/auth', () => ({
  fetchMe: vi.fn().mockResolvedValue({ username: 'alice', role: 'USER', phone: '13800000000', nickname: 'A', signature: '' }),
  changePassword: vi.fn(),
  deleteAccount,
}))
vi.mock('@/api/profile', () => ({
  updateProfile: vi.fn(), uploadAvatar: vi.fn(),
  rebindInit: vi.fn(), rebindVerifyOld: vi.fn(), rebindConfirm: vi.fn(),
}))

function renderProfile() {
  return render(
    <MemoryRouter initialEntries={['/profile']}>
      <Routes><Route path="/profile" element={<Profile />} /></Routes>
    </MemoryRouter>,
  )
}

describe('Profile account deletion', () => {
  it('two-step confirm + password then deletes', async () => {
    renderProfile()
    await waitFor(() => expect(screen.getByText('个人资料')).toBeInTheDocument())

    // Step 1: open the danger card and click 注销账号
    fireEvent.click(await screen.findByRole('button', { name: '注销账号' }))
    expect(screen.getByText(/此操作不可逆/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '继续' }))

    // Step 2: enter password and confirm
    await waitFor(() => fireEvent.change(screen.getByPlaceholderText('登录密码'), { target: { value: 'right' } }))
    fireEvent.click(screen.getByRole('button', { name: '确认注销' }))

    await waitFor(() => expect(deleteAccount).toHaveBeenCalledWith('right'))
  })
})
