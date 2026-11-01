import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MainLayout from './MainLayout'

vi.mock('../api/sites.api', () => ({
  useSite: () => ({ data: undefined }),
}))
vi.mock('../components/NotificationBell', () => ({ default: () => <span>알림 벨</span> }))
vi.mock('../components/chat/ChatPanel', () => ({ default: () => null }))
vi.mock('../components/LogoutButton', () => ({ default: () => <button type="button">로그아웃</button> }))

function stubWidth(width: number) {
  vi.stubGlobal('matchMedia', (query: string) => {
    const m = /max-width:\s*(\d+)px/.exec(query)
    return {
      matches: m ? width <= Number(m[1]) : false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
    }
  })
}

function renderLayout(path = '/dashboard') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<MainLayout />}>
          <Route path="/dashboard" element={<p>대시보드 본문</p>} />
          <Route path="/sites" element={<p>현장 본문</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MainLayout 반응형 내비게이션', () => {
  it('데스크톱(1440px)은 고정 사이드바를 보이고 메뉴 버튼을 두지 않는다', () => {
    stubWidth(1440)
    renderLayout()

    const nav = screen.getByRole('navigation', { name: '주 메뉴' })
    expect(within(nav).getByRole('button', { name: '대시보드' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByRole('button', { name: '메뉴 열기' })).not.toBeInTheDocument()
  })

  it.each([375, 768])('%ipx에서는 사이드바 대신 메뉴 버튼을 렌더한다', (width) => {
    stubWidth(width)
    renderLayout()

    expect(screen.getByRole('button', { name: '메뉴 열기' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: '주 메뉴' })).not.toBeInTheDocument()
    expect(screen.getByText('대시보드 본문')).toBeInTheDocument()
  })

  it('모바일 Drawer에서 메뉴를 고르면 이동하고 Drawer가 닫힌다', async () => {
    stubWidth(375)
    renderLayout()

    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    const nav = await screen.findByRole('navigation', { name: '주 메뉴' })
    expect(within(nav).getByRole('button', { name: '대시보드' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: '메뉴 열기' })).toHaveAttribute('aria-expanded', 'true')
    expect(document.querySelector('.bf-nav-drawer.ant-drawer-open')).not.toBeNull()

    fireEvent.click(within(nav).getByRole('button', { name: '현장 관리' }))

    expect(await screen.findByText('현장 본문')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '메뉴 열기' })).toHaveAttribute('aria-expanded', 'false')
    // jsdom은 닫힘 트랜지션을 끝내지 않으므로 Drawer 루트의 open 클래스로 상태를 확인한다
    await waitFor(() => {
      expect(document.querySelector('.bf-nav-drawer.ant-drawer-open')).toBeNull()
    })

    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    const reopened = await screen.findByRole('navigation', { name: '주 메뉴' })
    expect(within(reopened).getByRole('button', { name: '현장 관리' })).toHaveAttribute('aria-current', 'page')
    expect(within(reopened).getByRole('button', { name: '대시보드' })).not.toHaveAttribute('aria-current')
  })
})
