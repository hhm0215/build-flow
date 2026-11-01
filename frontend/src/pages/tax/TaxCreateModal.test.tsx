import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TaxCreateModal from './TaxCreateModal'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/taxes.api', () => ({
  useCreateTax: () => ({ mutateAsync, isPending: false }),
}))
vi.mock('../../api/sites.api', () => ({
  useSites: () => ({ data: [{ id: 4, siteName: '합성 현장', archivedAt: null, client: null }], isLoading: false }),
}))

beforeEach(() => {
  mutateAsync.mockReset()
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false, media: query, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
  }))
  const getComputedStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation((element) => getComputedStyle(element))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('TaxCreateModal', () => {
  it('현장 상세에서 열면 그 현장이 선택되어 있다', () => {
    render(<TaxCreateModal initialSiteId={4} onClose={vi.fn()} />)
    expect(screen.getByText('합성 현장 (#4)')).toBeInTheDocument()
  })

  it('빈 세액은 공급가액 10%로 채우고, 세액을 지우면 다시 자동 채움한다', async () => {
    render(<TaxCreateModal onClose={vi.fn()} />)
    const supply = screen.getByPlaceholderText('공급가액')
    const tax = screen.getByPlaceholderText('세액')
    fireEvent.change(supply, { target: { value: '600000' } })
    await waitFor(() => expect(tax).toHaveValue('₩ 60,000'))

    fireEvent.change(tax, { target: { value: '59000' } })
    fireEvent.change(supply, { target: { value: '700000' } })
    await waitFor(() => expect(supply).toHaveValue('₩ 700,000'))
    expect(tax).toHaveValue('₩ 59,000')

    fireEvent.change(tax, { target: { value: '' } })
    fireEvent.change(supply, { target: { value: '800000' } })
    await waitFor(() => expect(tax).toHaveValue('₩ 80,000'))
  })
})
