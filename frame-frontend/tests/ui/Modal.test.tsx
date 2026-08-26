import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Modal from '@/components/ui/Modal'

describe('Modal', () => {
  it('renders nothing when closed', () => {
    render(<Modal open={false} onClose={() => {}} title="T">body</Modal>)
    expect(screen.queryByText('body')).not.toBeInTheDocument()
    // Portal renders into document.body, not the container div.
    expect(document.body.textContent).not.toContain('body')
  })

  it('renders title + children when open, with dialog role', () => {
    render(<Modal open={true} onClose={() => {}} title="确认操作">content here</Modal>)
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByText('确认操作')).toBeInTheDocument()
    expect(screen.getByText('content here')).toBeInTheDocument()
  })

  it('calls onClose on Escape', async () => {
    const onClose = vi.fn()
    render(<Modal open={true} onClose={onClose} title="T">x</Modal>)
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('calls onClose on backdrop click', async () => {
    const onClose = vi.fn()
    render(<Modal open={true} onClose={onClose} title="T">x</Modal>)
    // The backdrop is the outermost portal element.
    const backdrop = document.body.querySelector('.fixed.inset-0') as HTMLElement
    await userEvent.click(backdrop)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('focuses the first focusable element on open', async () => {
    render(
      <Modal open={true} onClose={() => {}} title="T">
        <button>action</button>
      </Modal>,
    )
    const action = screen.getByRole('button', { name: 'action' })
    expect(action).toHaveFocus()
  })
})
