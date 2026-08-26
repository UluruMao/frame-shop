// frame-frontend/tests/ui/Button.test.tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Button from '@/components/ui/Button'

describe('Button', () => {
  it('renders children', () => {
    render(<Button>Save profile</Button>)
    expect(screen.getByRole('button', { name: 'Save profile' })).toBeInTheDocument()
  })
  it('applies primary variant classes by default', () => {
    render(<Button>Click</Button>)
    expect(screen.getByRole('button')).toHaveClass('bg-brand-600')
  })
  it('applies danger variant classes', () => {
    render(<Button variant="danger">Delete</Button>)
    expect(screen.getByRole('button')).toHaveClass('bg-red-600')
  })
  it('applies success variant classes', () => {
    render(<Button variant="success">支付</Button>)
    expect(screen.getByRole('button')).toHaveClass('bg-green-600')
  })
  it('disables and shows spinner when loading', () => {
    render(<Button loading>Loading</Button>)
    const btn = screen.getByRole('button')
    expect(btn).toBeDisabled()
    expect(btn.querySelector('svg')).toBeInTheDocument()
  })
  it('fires onClick', async () => {
    const fn = vi.fn()
    render(<Button onClick={fn}>Go</Button>)
    await userEvent.click(screen.getByRole('button'))
    expect(fn).toHaveBeenCalledTimes(1)
  })
})
