import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Warranty } from '../../types'
import WarrantyListPage from './WarrantyListPage'

const warranties = vi.hoisted(() => ({ items: [] as Warranty[] }))

vi.mock('../../api/warranties.api', () => ({
  useWarranties: () => ({ data: warranties.items, isLoading: false, isError: false, refetch: vi.fn() }),
  useExpiringWarranties: () => ({ data: [] }),
  useDeleteWarranty: () => ({ mutate: vi.fn() }),
  useCreateWarranty: () => ({ mutate: vi.fn(), isPending: false }),
}))
vi.mock('./WarrantyUploadModal', () => ({ default: () => null }))
vi.mock('./WarrantyEditModal', () => ({
  default: ({ warranty }: { warranty: Warranty }) => <div data-testid="edit-target">{warranty.id}</div>,
}))

const warranty = (id: number, ocrStatus: Warranty['ocrStatus'], endDate: string | null): Warranty => ({
  id,
  siteId: 1,
  insuranceCompany: ocrStatus === 'PENDING' ? null : '서울보증보험',
  policyNumber: null,
  coverageAmount: null,
  startDate: null,
  endDate,
  memo: null,
  daysUntilExpiry: 0,
  expired: false,
  ocrStatus,
  createdAt: '2026-09-20T00:00:00',
  updatedAt: '2026-09-20T00:00:00',
})

beforeEach(() => {
  warranties.items = [warranty(1, 'PENDING', null), warranty(2, 'FAILED', null)]
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

describe('WarrantyListPage', () => {
  it('OCR 처리 중인 행은 수정하지 못하고 실패 행은 수동 보정을 연다', () => {
    render(<MemoryRouter><WarrantyListPage /></MemoryRouter>)
    expect(screen.getAllByRole('button', { name: /수정/ })).toHaveLength(1)
    expect(screen.getAllByText('기간 미입력')).toHaveLength(2)
    expect(screen.getAllByText('확인 필요')).toHaveLength(2)
    fireEvent.click(screen.getByRole('button', { name: '서울보증보험 수정' }))
    expect(screen.getByTestId('edit-target')).toHaveTextContent('2')
  })

  it('종료일 없는 OCR 항목은 유효 필터에서 제외한다', () => {
    render(<MemoryRouter initialEntries={['/warranties?status=VALID']}><WarrantyListPage /></MemoryRouter>)
    expect(screen.getByText('필터 조건에 맞는 보증보험이 없습니다.')).toBeInTheDocument()
  })
})
