import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import Table from '@/components/ui/Table'

interface Row { id: number; name: string }

const DATA: Row[] = [
  { id: 1, name: 'Widget' },
  { id: 2, name: 'Gadget' },
]

describe('Table', () => {
  it('renders headers and rows', () => {
    render(
      <Table
        columns={[
          { key: 'id', header: 'ID' },
          { key: 'name', header: '名称' },
        ]}
        data={DATA}
        rowKey={(r) => String(r.id)}
      />,
    )
    expect(screen.getByText('ID')).toBeInTheDocument()
    expect(screen.getByText('名称')).toBeInTheDocument()
    expect(screen.getByText('Widget')).toBeInTheDocument()
    expect(screen.getByText('Gadget')).toBeInTheDocument()
  })

  it('uses render for custom cells', () => {
    render(
      <Table
        columns={[{ key: 'name', header: '名称', render: (r: Row) => <strong>{r.name.toUpperCase()}</strong> }]}
        data={DATA}
        rowKey={(r) => String(r.id)}
      />,
    )
    expect(screen.getByText('WIDGET')).toBeInTheDocument()
  })

  it('shows empty content when data is empty', () => {
    render(
      <Table
        columns={[{ key: 'id', header: 'ID' }]}
        data={[]}
        rowKey={(r: Row) => String(r.id)}
        empty={<p>暂无数据</p>}
      />,
    )
    expect(screen.getByText('暂无数据')).toBeInTheDocument()
  })
})
