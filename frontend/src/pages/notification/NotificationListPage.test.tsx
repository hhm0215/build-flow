import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Notification } from '../../types'
import NotificationListPage from './NotificationListPage'

const fixture = vi.hoisted(() => ({
  items: [] as Notification[],
  unreadCount: 0,
  unreadLoading: false,
  unreadError: false,
  unreadRefetch: vi.fn(),
  markAllAsRead: vi.fn(),
}))

vi.mock('../../api/notifications.api', () => ({
  useNotifications: () => ({ data: fixture.items, isLoading: false, isError: false }),
  useUnreadCount: () => ({ data: fixture.unreadCount, isLoading: fixture.unreadLoading, isError: fixture.unreadError, refetch: fixture.unreadRefetch }),
  useMarkAsRead: () => ({ mutate: vi.fn() }),
  useMarkAllAsRead: () => ({ mutate: fixture.markAllAsRead }),
}))

beforeEach(() => {
  fixture.items = []
  fixture.unreadCount = 0
  fixture.unreadLoading = false
  fixture.unreadError = false
  fixture.unreadRefetch.mockReset()
  fixture.markAllAsRead.mockReset()
})

describe('NotificationListPage', () => {
  it('발행되는 모든 이벤트를 사용자용 분류명으로 표시한다', () => {
    const expected = [
      ['ESTIMATE_PARSED', '견적'],
      ['ESTIMATE_DELETED', '견적'],
      ['PURCHASE_REGISTERED', '매입'],
      ['PURCHASE_UPDATED', '매입'],
      ['PURCHASE_DELETED', '매입'],
      ['TAX_REGISTERED', '세금계산서'],
      ['TAX_PAYMENT_CONFIRMED', '입금'],
      ['WARRANTY_EXPIRING', '보증'],
    ] as const
    fixture.items = expected.map(([type], index) => ({
      id: index + 1,
      type,
      message: `알림 ${index + 1}`,
      siteId: null,
      read: true,
      createdAt: '2026-10-07T00:00:00',
    }))

    render(<NotificationListPage />)

    for (const [type] of expected) {
      expect(screen.queryByText(type)).not.toBeInTheDocument()
    }
    for (const [, label] of expected) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0)
    }
  })

  it('미읽음 건수 조회 실패 시 오래된 건수로 전체 읽음을 실행하지 않고 재시도한다', () => {
    fixture.unreadCount = 3
    fixture.unreadError = true
    render(<NotificationListPage />)

    expect(screen.getByRole('alert')).toHaveTextContent('읽지 않은 알림 건수를 불러오지 못했습니다.')
    expect(screen.queryByText('3건')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '전체 읽음' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(fixture.unreadRefetch).toHaveBeenCalledOnce()
    expect(fixture.markAllAsRead).not.toHaveBeenCalled()
  })

  it('미읽음 건수 조회 성공일 때만 전체 읽음을 허용한다', () => {
    fixture.unreadCount = 3
    render(<NotificationListPage />)

    expect(screen.getByText('3건')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '전체 읽음' }))
    expect(fixture.markAllAsRead).toHaveBeenCalledOnce()
  })
})
