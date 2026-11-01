import { fireEvent, render, screen } from '@testing-library/react'
import { AxiosError, AxiosHeaders } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SiteDetailPage from './SiteDetailPage'

const { state, empty } = vi.hoisted(() => ({
  state: { error: null as unknown },
  empty: () => ({ data: [], isLoading: false, isError: false, refetch: () => undefined }),
}))

vi.mock('../../api/sites.api', () => ({
  useSite: () => ({
    data: undefined,
    isLoading: false,
    isError: state.error !== null,
    error: state.error,
    refetch: vi.fn(),
  }),
  useUpdateSiteStatus: () => ({ mutate: vi.fn(), isPending: false }),
  useArchiveSite: () => ({ mutate: vi.fn(), isPending: false }),
  useRestoreSite: () => ({ mutate: vi.fn(), isPending: false }),
}))
vi.mock('../../api/estimates.api', () => ({ useEstimates: empty }))
vi.mock('../../api/purchases.api', () => ({ usePurchases: empty }))
vi.mock('../../api/taxes.api', () => ({ useTaxes: empty }))
vi.mock('../../api/warranties.api', () => ({ useWarranties: empty }))

function httpError(status: number) {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig
  const response = { config, status, statusText: '', headers: {}, data: null } as AxiosResponse
  return new AxiosError('fail', String(status), config, null, response)
}

function LocationProbe() {
  return <output data-testid="location">{useLocation().pathname}</output>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/sites/:id" element={<SiteDetailPage />} />
        <Route path="/sites" element={<p>현장 목록</p>} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  state.error = null
})

describe('SiteDetailPage — 없는 현장', () => {
  it('API가 404면 "현장을 찾을 수 없습니다"와 목록으로 버튼을 보여 준다', () => {
    state.error = httpError(404)
    renderAt('/sites/999999')

    expect(screen.getByText('현장을 찾을 수 없습니다')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '목록으로' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/sites')
    expect(screen.getByText('현장 목록')).toBeInTheDocument()
  })

  it('404가 아닌 오류는 기존 조회 실패 안내와 다시 시도를 유지한다', () => {
    state.error = httpError(500)
    renderAt('/sites/4')

    expect(screen.queryByText('현장을 찾을 수 없습니다')).not.toBeInTheDocument()
    expect(screen.getByText('데이터를 불러오는 중 오류가 발생했습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /다시 시도/ })).toBeInTheDocument()
  })

  it.each(['abc', '0', '-3', '1.5'])('잘못된 ID(%s)는 로딩에 멈추지 않고 같은 안내를 보여 준다', (id) => {
    renderAt(`/sites/${id}`)

    expect(screen.getByText('현장을 찾을 수 없습니다')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '목록으로' })).toBeInTheDocument()
  })
})
