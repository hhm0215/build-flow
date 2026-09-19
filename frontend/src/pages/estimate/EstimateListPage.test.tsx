import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Estimate } from '../../types'
import EstimateListPage from './EstimateListPage'

const estimates = vi.hoisted(() => ({ items: [] as Estimate[] }))

vi.mock('../../api/estimates.api', () => ({
  useEstimates: () => ({ data: estimates.items, isLoading: false, isError: false }),
  useCreateEstimate: () => ({ mutate: vi.fn(), isPending: false }),
}))
vi.mock('./UploadParseModal', () => ({ default: () => null }))
vi.mock('./EstimateConfirmModal', () => ({
  default: ({ estimate }: { estimate: Estimate }) => <div data-testid="confirm-target">{estimate.title}</div>,
}))
vi.mock('./EstimateEditModal', () => ({
  default: ({ estimate }: { estimate: Estimate }) => <div data-testid="edit-target">{estimate.title}</div>,
}))
vi.mock('./EstimateDeleteModal', () => ({
  default: ({ estimate }: { estimate: Estimate }) => <div data-testid="delete-target">{estimate.title}</div>,
}))

const estimate = (id: number, status: Estimate['status']): Estimate => ({
  id,
  siteId: 3,
  title: `${status} 견적 ${id}`,
  status,
  estimateDate: '2026-09-19',
  totalAmount: 120000,
  memo: '',
  items: [],
  createdAt: '2026-09-19T00:00:00',
  updatedAt: '2026-09-19T00:00:00',
})

beforeEach(() => {
  estimates.items = [estimate(1, 'DRAFT'), estimate(2, 'CONFIRMED')]
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('EstimateListPage', () => {
  it('작성 중인 견적에만 확정 버튼을 표시하고 해당 견적을 선택한다', () => {
    render(<MemoryRouter><EstimateListPage /></MemoryRouter>)

    const buttons = screen.getAllByRole('button', { name: '확정' })
    expect(buttons).toHaveLength(1)
    fireEvent.click(buttons[0])
    expect(screen.getByTestId('confirm-target')).toHaveTextContent('DRAFT 견적 1')
  })

  it('작성 중인 견적에만 수정·삭제 버튼을 표시하고 대상 견적을 연다', () => {
    render(<MemoryRouter><EstimateListPage /></MemoryRouter>)

    expect(screen.getAllByRole('button', { name: '수정' })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: '삭제' })).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '수정' }))
    expect(screen.getByTestId('edit-target')).toHaveTextContent('DRAFT 견적 1')
    fireEvent.click(screen.getByRole('button', { name: '삭제' }))
    expect(screen.getByTestId('delete-target')).toHaveTextContent('DRAFT 견적 1')
  })
})
