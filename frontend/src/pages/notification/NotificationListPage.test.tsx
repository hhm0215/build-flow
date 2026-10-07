import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Notification } from '../../types'
import NotificationListPage from './NotificationListPage'

const fixture = vi.hoisted(() => ({ items: [] as Notification[] }))

vi.mock('../../api/notifications.api', () => ({
  useNotifications: () => ({ data: fixture.items, isLoading: false, isError: false }),
  useUnreadCount: () => ({ data: 0 }),
  useMarkAsRead: () => ({ mutate: vi.fn() }),
  useMarkAllAsRead: () => ({ mutate: vi.fn() }),
}))

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
})
