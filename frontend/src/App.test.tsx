import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { useAuthStore } from './stores/authStore'

vi.mock('./api/sites.api', () => ({
  useSite: () => ({ data: undefined }),
}))
vi.mock('./pages/auth/LoginPage', () => ({ default: () => <p>로그인 화면</p> }))
vi.mock('./pages/dashboard/DashboardPage', () => ({ default: () => <p>대시보드 본문</p> }))
vi.mock('./components/NotificationBell', () => ({ default: () => <span>알림 벨</span> }))
vi.mock('./components/chat/ChatPanel', () => ({ default: () => null }))
vi.mock('./components/LogoutButton', () => ({ default: () => <button type="button">로그아웃</button> }))

function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname}</output>
}

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <LocationProbe />
    </MemoryRouter>,
  )
}

afterEach(() => {
  useAuthStore.setState({ accessToken: null, refreshToken: null, sessionId: null, isAuthenticated: false })
})

describe('App 라우트 — 없는 주소', () => {
  it('로그인 상태면 레이아웃 안에서 404 안내와 대시보드 버튼을 보여 준다', async () => {
    useAuthStore.setState({ accessToken: 'a', refreshToken: 'r', sessionId: 's', isAuthenticated: true })
    renderApp('/clients')

    expect(screen.getByText('페이지를 찾을 수 없습니다')).toBeInTheDocument()
    // 레이아웃(주 메뉴)이 함께 렌더돼 메뉴로 바로 이동할 수 있다
    expect(screen.getByRole('navigation', { name: '주 메뉴' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '대시보드로' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/dashboard')
    expect(await screen.findByText('대시보드 본문')).toBeInTheDocument()
  })

  it('로그인 안 된 상태면 없는 주소도 로그인 화면으로 보낸다', () => {
    renderApp('/clients')

    expect(screen.getByTestId('location')).toHaveTextContent('/login')
    expect(screen.getByText('로그인 화면')).toBeInTheDocument()
    expect(screen.queryByText('페이지를 찾을 수 없습니다')).not.toBeInTheDocument()
  })
})
