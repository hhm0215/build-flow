import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import axiosInstance from './axiosInstance'
import { useNotifications, useUnreadCount } from './notifications.api'

afterEach(() => vi.restoreAllMocks())

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

describe('notification wire contract', () => {
  it('maps the backend eventType into the UI type without losing read state', async () => {
    vi.spyOn(axiosInstance, 'get').mockResolvedValue({
      data: {
        success: true,
        data: [{ id: 4, eventType: 'PURCHASE_REGISTERED', message: 'test', siteId: 2, read: false, createdAt: '2026-10-03T00:00:00' }],
      },
    })

    const { result } = renderHook(() => useNotifications(), { wrapper: createWrapper() })
    await waitFor(() => expect(result.current.data?.[0]).toMatchObject({
      id: 4, type: 'PURCHASE_REGISTERED', read: false,
    }))
  })

  it('unwraps the backend unread-count object into a number for the badge', async () => {
    vi.spyOn(axiosInstance, 'get').mockResolvedValue({ data: { success: true, data: { count: 3 } } })

    const { result } = renderHook(() => useUnreadCount(), { wrapper: createWrapper() })
    await waitFor(() => expect(result.current.data).toBe(3))
  })
})
