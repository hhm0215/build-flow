import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import dayjs from 'dayjs'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Estimate } from '../../types'
import EstimateListPage from './EstimateListPage'

const estimates = vi.hoisted(() => ({ items: [] as Estimate[], create: vi.fn() }))

vi.mock('antd', async (importOriginal) => {
  const actual = await importOriginal<typeof import('antd')>()
  return {
    ...actual,
    DatePicker: ({ onChange }: { onChange?: (date: dayjs.Dayjs, dateString: string) => void }) => (
      <input aria-label="견적일" onChange={(event) => onChange?.(dayjs(event.target.value), event.target.value)} />
    ),
  }
})
vi.mock('../../components/SiteSelect', () => ({
  default: ({ onChange }: { onChange?: (siteId: number) => void }) => (
    <input aria-label="현장" onChange={(event) => onChange?.(Number(event.target.value))} />
  ),
}))
vi.mock('../../components/filters/FilterDateRange', () => ({ default: () => null }))

vi.mock('../../api/estimates.api', () => ({
  useEstimates: () => ({ data: estimates.items, isLoading: false, isError: false }),
  useCreateEstimate: () => ({ mutateAsync: estimates.create, isPending: false }),
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
  estimates.create.mockReset()
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
  const fillCreateForm = () => {
    fireEvent.click(screen.getByRole('button', { name: '견적서 작성' }))
    fireEvent.change(screen.getByRole('textbox', { name: '현장' }), { target: { value: '3' } })
    fireEvent.change(screen.getByPlaceholderText('견적 제목'), { target: { value: '테스트 견적' } })
    fireEvent.change(screen.getByRole('textbox', { name: '견적일' }), { target: { value: '2026-10-19' } })
    fireEvent.click(screen.getByRole('button', { name: '항목 추가' }))
    fireEvent.change(screen.getByPlaceholderText('품목명'), { target: { value: '자재' } })
    fireEvent.change(screen.getByPlaceholderText('EA'), { target: { value: 'EA' } })
    fireEvent.change(screen.getByPlaceholderText('수량'), { target: { value: '2' } })
    fireEvent.change(screen.getByPlaceholderText('단가'), { target: { value: '1000' } })
  }

  it('저장 중 중복 제출을 막고 실패 시 입력값과 오류를 유지한다', async () => {
    let rejectCreate: (error: unknown) => void = () => {}
    estimates.create.mockImplementation(() => new Promise((_, reject) => { rejectCreate = reject }))
    render(<MemoryRouter><EstimateListPage /></MemoryRouter>)
    fillCreateForm()

    const submit = screen.getByRole('button', { name: /^작성$/ })
    fireEvent.click(submit)
    await waitFor(() => expect(estimates.create).toHaveBeenCalledTimes(1))
    fireEvent.click(submit)
    expect(estimates.create).toHaveBeenCalledTimes(1)

    rejectCreate({ response: { data: { error: '현장을 다시 확인하세요.' } } })
    expect(await screen.findByText('현장을 다시 확인하세요.')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('견적 제목')).toHaveValue('테스트 견적')
  })

  it('엑셀 버튼을 파일 보존이나 엑셀 편집으로 오해하지 않도록 AI 파싱으로 표시한다', () => {
    render(<MemoryRouter><EstimateListPage /></MemoryRouter>)

    expect(screen.getByRole('button', { name: '공내역서 AI 파싱' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '엑셀로 작성' })).not.toBeInTheDocument()
  })

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
