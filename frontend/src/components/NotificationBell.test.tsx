import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NotificationBell from './NotificationBell'

const query = vi.hoisted(() => ({ unreadCount: 3, unreadError: false }))

vi.mock('../api/notifications.api', () => ({
  useUnreadCount: () => ({ data: query.unreadCount, isLoading: false, isError: query.unreadError }),
  useNotifications: () => ({ data: [] }),
  useMarkAsRead: () => ({ mutate: vi.fn() }),
}))

beforeEach(() => {
  query.unreadCount = 3
  query.unreadError = false
})

const renderBell = () => render(<MemoryRouter><NotificationBell /></MemoryRouter>)

describe('NotificationBell unread count', () => {
  it('재조회 실패 시 남은 캐시 숫자를 배지와 메뉴에 표시하지 않는다', () => {
    query.unreadError = true
    renderBell()

    expect(screen.queryByText('3')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '알림' }))
    expect(screen.getByRole('status')).toHaveTextContent('미읽음 건수 조회 실패')
    expect(screen.queryByText('미읽음 3건')).not.toBeInTheDocument()
  })

  it('건수 조회 성공 시 배지와 메뉴에 숫자를 표시한다', () => {
    renderBell()

    expect(screen.getByText('3')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '알림' }))
    expect(screen.getByText('미읽음 3건')).toBeInTheDocument()
  })
})
