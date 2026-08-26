import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Tabs from '@/components/ui/Tabs'

describe('Tabs', () => {
  it('renders the default tab content', () => {
    render(
      <Tabs
        tabs={[
          { key: 'a', label: '全部', content: <p>all content</p> },
          { key: 'b', label: '未付款', content: <p>pending content</p> },
        ]}
        defaultKey="a"
      />,
    )
    expect(screen.getByText('all content')).toBeInTheDocument()
    expect(screen.queryByText('pending content')).not.toBeInTheDocument()
  })

  it('switches content on tab click', async () => {
    render(
      <Tabs
        tabs={[
          { key: 'a', label: '全部', content: <p>all content</p> },
          { key: 'b', label: '未付款', content: <p>pending content</p> },
        ]}
        defaultKey="a"
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: '未付款' }))
    expect(screen.getByText('pending content')).toBeInTheDocument()
    expect(screen.queryByText('all content')).not.toBeInTheDocument()
  })
})
