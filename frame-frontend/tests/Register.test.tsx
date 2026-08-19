import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import Register from '@/pages/Register'

/**
 * Register flow test (UC001).
 *
 * Boundary: @/api/auth is mocked — getCaptcha, sendCode, register are vi.fn()s.
 * No real SMS is sent. The test fills the form, clicks "Get code" (asserting
 * sendCode was called with purpose 'REGISTER'), then submits and asserts
 * register() was called with agreed=true.
 */

vi.mock('@/api/auth', () => ({
  register: vi.fn(),
  sendCode: vi.fn(),
  getCaptcha: vi.fn(),
}))

import { register, sendCode, getCaptcha } from '@/api/auth'
import type { CaptchaResponse } from '@/api/types'

function mockCaptcha(overrides: Partial<CaptchaResponse> = {}): CaptchaResponse {
  return { captchaId: 'cap-123', svgImage: '<svg></svg>', ...overrides }
}

function renderRegister() {
  return render(
    <MemoryRouter>
      <Register />
    </MemoryRouter>,
  )
}

describe('Register — SMS-gated flow (UC001)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getCaptcha).mockResolvedValue(mockCaptcha())
    vi.mocked(sendCode).mockResolvedValue(undefined)
    vi.mocked(register).mockResolvedValue({ id: 1, username: 'alice', role: 'USER' })
  })

  it('sends an SMS code with purpose REGISTER, then registers with agreed=true', async () => {
    const user = userEvent.setup()
    renderRegister()

    // CaptchaImage loads on mount — wait for it.
    await waitFor(() => {
      expect(vi.mocked(getCaptcha)).toHaveBeenCalledTimes(1)
    })

    // Fill the form.
    await user.type(screen.getByPlaceholderText(/username/i), 'alice')
    await user.type(screen.getByPlaceholderText(/password/i), 'Secret123!')
    await user.type(screen.getByPlaceholderText(/phone/i), '13800000000')
    // Type the captcha answer (input inside CaptchaImage, placeholder "captcha").
    await user.type(screen.getByPlaceholderText('captcha'), 'ABCD')

    // Click "Get code" → sendCode called with purpose REGISTER.
    await user.click(screen.getByRole('button', { name: /get code/i }))
    await waitFor(() => {
      expect(vi.mocked(sendCode)).toHaveBeenCalledTimes(1)
    })
    expect(vi.mocked(sendCode)).toHaveBeenCalledWith(
      expect.objectContaining({ phone: '13800000000', purpose: 'REGISTER' }),
    )

    // Enter the verification code.
    await user.type(screen.getByPlaceholderText(/verification code/i), '123456')

    // Agree to the terms.
    await user.click(screen.getByRole('checkbox'))

    // Submit → register called with agreed=true.
    await user.click(screen.getByRole('button', { name: /register/i }))
    await waitFor(() => {
      expect(vi.mocked(register)).toHaveBeenCalledTimes(1)
    })
    expect(vi.mocked(register)).toHaveBeenCalledWith(
      expect.objectContaining({ agreed: true, username: 'alice', phone: '13800000000' }),
    )
  })

  it('blocks submission when the terms checkbox is not agreed', async () => {
    const user = userEvent.setup()
    renderRegister()

    await waitFor(() => {
      expect(vi.mocked(getCaptcha)).toHaveBeenCalledTimes(1)
    })

    await user.type(screen.getByPlaceholderText(/username/i), 'bob')
    await user.type(screen.getByPlaceholderText(/password/i), 'Secret123!')
    await user.type(screen.getByPlaceholderText(/phone/i), '13800000000')
    await user.type(screen.getByPlaceholderText('captcha'), 'ABCD')
    await user.type(screen.getByPlaceholderText(/verification code/i), '123456')

    // Do NOT check the terms box → submit is clicked but register is NOT called.
    await user.click(screen.getByRole('button', { name: /register/i }))

    expect(screen.getByText(/please agree to the terms/i)).toBeInTheDocument()
    expect(vi.mocked(register)).not.toHaveBeenCalled()
  })
})
