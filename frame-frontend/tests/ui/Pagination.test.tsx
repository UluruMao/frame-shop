import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Pagination from '@/components/ui/Pagination'

describe('Pagination', () => {
  it('disables 上一页 on the first page', () => {
    render(<Pagination current={1} size={12} total={36} onChange={() => {}} />)
    expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '下一页' })).not.toBeDisabled()
  })

  it('disables 下一页 on the last page', () => {
    render(<Pagination current={3} size={12} total={36} onChange={() => {}} />)
    expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled()
  })

  it('calls onChange with the target page', async () => {
    const onChange = vi.fn()
    render(<Pagination current={1} size={12} total={36} onChange={onChange} />)
    await userEvent.click(screen.getByRole('button', { name: '下一页' }))
    expect(onChange).toHaveBeenCalledWith(2)
  })
})
