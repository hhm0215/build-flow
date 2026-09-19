import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import axiosInstance from './axiosInstance'
import { useConfirmEstimate } from './estimates.api'
import { SITES_KEY } from './sites.api'

afterEach(() => vi.restoreAllMocks())

describe('useConfirmEstimate', () => {
  it('확정 성공 후 해당 현장의 손익 캐시를 stale 처리한다', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 300_000 } } })
    queryClient.setQueryData(SITES_KEY.profit(3), { siteId: 3, totalEstimateAmount: 0 })
    vi.spyOn(axiosInstance, 'patch').mockResolvedValue({
      data: { data: { id: 42, siteId: 3, status: 'CONFIRMED' } },
    })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useConfirmEstimate(), { wrapper })

    await act(async () => { await result.current.mutateAsync(42) })

    expect(queryClient.getQueryState(SITES_KEY.profit(3))?.isInvalidated).toBe(true)
  })
})
