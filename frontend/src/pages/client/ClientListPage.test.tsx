import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Client, Site } from '../../types'
import ClientListPage from './ClientListPage'

const state = vi.hoisted(() => ({
  clients: [] as Client[],
  sites: [] as Partial<Site>[],
  archive: vi.fn(),
  restore: vi.fn(),
}))

vi.mock('../../api/clients.api', () => ({
  useClients: () => ({ data: state.clients, isLoading: false, isError: false, refetch: vi.fn() }),
  useArchiveClient: () => ({ mutate: state.archive, isPending: false }),
  useRestoreClient: () => ({ mutate: state.restore, isPending: false }),
}))
vi.mock('../../api/sites.api', () => ({
  useSites: () => ({ data: state.sites, isError: false }),
}))
vi.mock('../site/ClientEditModal', () => ({
  default: ({ client }: { client: Client }) => <div data-testid="edit-target">{client.companyName}</div>,
}))
vi.mock('../site/ClientCreateModal', () => ({
  default: ({ open }: { open: boolean }) => (open ? <div data-testid="create-modal" /> : null),
}))

const client = (id: number, companyName: string, extra: Partial<Client> = {}): Client => ({
  id, companyName, representative: null, businessNo: null, phone: null, email: null,
  address: null, memo: null, archivedAt: null,
  createdAt: '2026-10-01T00:00:00', updatedAt: '2026-10-01T00:00:00',
  ...extra,
})

function renderPage(path = '/clients') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ClientListPage />
    </MemoryRouter>,
  )
}

function rowOf(name: string) {
  return screen.getByText(name).closest('tr') as HTMLElement
}

beforeEach(() => {
  vi.useRealTimers()
  state.archive.mockReset()
  state.restore.mockReset()
  state.clients = [
    client(1, '삼성물산', { representative: '김대표', phone: '02-1234-5678' }),
    client(2, '현장 없는 거래처', { businessNo: '111-22-33333' }),
    client(3, '보관 거래처', { archivedAt: '2026-10-20T09:00:00' }),
  ]
  state.sites = [
    { id: 10, client: state.clients[0], archivedAt: null },
    { id: 11, client: state.clients[0], archivedAt: '2026-10-02T00:00:00' },
    { id: 12, client: state.clients[2], archivedAt: null },
    { id: 13, client: null, archivedAt: null },
  ]
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

describe('ClientListPage', () => {
  it('기본은 보관 거래처를 빼고, 현장에 연결되지 않은 거래처와 연결 현장 수를 보여 준다', () => {
    renderPage()

    expect(screen.getByText('삼성물산')).toBeInTheDocument()
    expect(screen.getByText('현장 없는 거래처')).toBeInTheDocument()
    expect(screen.queryByText('보관 거래처')).not.toBeInTheDocument()
    expect(screen.getByTestId('linked-sites-1')).toHaveTextContent('2곳 (보관 1)')
    expect(screen.getByTestId('linked-sites-2')).toHaveTextContent('0곳')
    expect(screen.getByRole('columnheader', { name: '처리' })).toBeInTheDocument()
  })

  it('보관 포함 필터면 보관 거래처를 흐리게 보관 표시와 복원 버튼으로 보여 준다', () => {
    renderPage('/clients?archive=ALL')

    const row = rowOf('보관 거래처')
    expect(row).toHaveAttribute('data-archived', 'true')
    expect(within(row).getByText('보관')).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: /복원/ })).toBeInTheDocument()
    expect(within(rowOf('삼성물산')).getByRole('button', { name: /보관/ })).toBeInTheDocument()
  })

  it('보관된 거래처만 필터는 보관 거래처만 남긴다', () => {
    renderPage('/clients?archive=ARCHIVED')

    expect(screen.getByText('보관 거래처')).toBeInTheDocument()
    expect(screen.queryByText('삼성물산')).not.toBeInTheDocument()
  })

  it('업체명·대표자·사업자번호로 검색한다', async () => {
    vi.useFakeTimers()
    renderPage()

    fireEvent.change(screen.getByPlaceholderText(/업체명, 대표자/), { target: { value: '111-22' } })
    act(() => { vi.advanceTimersByTime(300) })
    expect(screen.getByText('현장 없는 거래처')).toBeInTheDocument()
    expect(screen.queryByText('삼성물산')).not.toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText(/업체명, 대표자/), { target: { value: '김대표' } })
    act(() => { vi.advanceTimersByTime(300) })
    expect(screen.getByText('삼성물산')).toBeInTheDocument()
    expect(screen.queryByText('현장 없는 거래처')).not.toBeInTheDocument()
    vi.useRealTimers()
  })

  it('수정 버튼은 그 거래처로 수정 모달을 열고, 등록 버튼은 등록 모달을 연다', () => {
    renderPage()

    fireEvent.click(within(rowOf('현장 없는 거래처')).getByRole('button', { name: /수정/ }))
    expect(screen.getByTestId('edit-target')).toHaveTextContent('현장 없는 거래처')

    fireEvent.click(screen.getByRole('button', { name: /거래처 등록/ }))
    expect(screen.getByTestId('create-modal')).toBeInTheDocument()
  })

  it('보관 버튼은 확인 후 보관 요청을 보낸다', async () => {
    renderPage()

    fireEvent.click(within(rowOf('현장 없는 거래처')).getByRole('button', { name: /보관/ }))
    const popover = await screen.findByRole('tooltip')
    fireEvent.click(within(popover).getByRole('button', { name: '보관' }))
    await waitFor(() => expect(state.archive).toHaveBeenCalledWith(2, expect.any(Object)))
    expect(state.restore).not.toHaveBeenCalled()
  })

  it('복원 버튼은 확인 후 복원 요청을 보낸다', async () => {
    renderPage('/clients?archive=ARCHIVED')

    fireEvent.click(within(rowOf('보관 거래처')).getByRole('button', { name: /복원/ }))
    const popover = await screen.findByRole('tooltip')
    fireEvent.click(within(popover).getByRole('button', { name: '복원' }))
    await waitFor(() => expect(state.restore).toHaveBeenCalledWith(3, expect.any(Object)))
    expect(state.archive).not.toHaveBeenCalled()
  })

  it('업체명이나 연결 현장 수를 누르면 그 거래처의 현장 목록으로 이동한다', () => {
    function SitesProbe() {
      const location = useLocation()
      return <div data-testid="sites-location">{`${location.pathname}${location.search}`}</div>
    }
    const renderRoutes = () => render(
      <MemoryRouter initialEntries={['/clients']}>
        <Routes>
          <Route path="/clients" element={<ClientListPage />} />
          <Route path="/sites" element={<SitesProbe />} />
        </Routes>
      </MemoryRouter>,
    )

    const first = renderRoutes()
    fireEvent.click(screen.getByRole('link', { name: '현장 없는 거래처' }))
    expect(screen.getByTestId('sites-location')).toHaveTextContent('/sites?clientId=2')
    first.unmount()

    // 보관 현장이 섞인 거래처는 연결 현장 수와 결과가 같도록 보관 포함으로 연다
    renderRoutes()
    fireEvent.click(within(screen.getByTestId('linked-sites-1')).getByRole('link'))
    expect(screen.getByTestId('sites-location')).toHaveTextContent('/sites?clientId=1&archive=ALL')
  })
})
