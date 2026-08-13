import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import Dashboard from '@/pages/Dashboard'

describe('Dashboard', () => {
  it('renders the heading and the four stat tiles', () => {
    render(<Dashboard />)
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    // One of the stat labels — proves the stats array rendered.
    expect(screen.getByText('Total sales')).toBeInTheDocument()
    // The chart caption.
    expect(screen.getByText('Monthly sales')).toBeInTheDocument()
  })

  it('renders all four stat values', () => {
    render(<Dashboard />)
    expect(screen.getByText('$12,480')).toBeInTheDocument()
    expect(screen.getByText('284')).toBeInTheDocument()
    expect(screen.getByText('18')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
  })
})
