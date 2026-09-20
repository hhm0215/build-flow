import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import axiosInstance from './axiosInstance'
import { TAXES_KEY, useConfirmPayment } from './taxes.api'

afterEach(() => vi.restoreAllMocks())

describe('useConfirmPayment', () => {
  it('입금일 JSON 본문을 보내고 세금계산서 목록을 무효화한다', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 300_000 } } })
    queryClient.setQueryData(TAXES_KEY.list(), [{ id: 17, paymentConfirmed: false }])
    const patch = vi.spyOn(axiosInstance, 'patch').mockResolvedValue({
      data: { data: { id: 17, paymentConfirmed: true, paymentDate: '2026-09-20' } },
    })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useConfirmPayment(), { wrapper })

    await act(async () => { await result.current.mutateAsync({ id: 17, paymentDate: '2026-09-20' }) })

    expect(patch).toHaveBeenCalledWith('/taxes/17/confirm-payment', { paymentDate: '2026-09-20' })
    expect(queryClient.getQueryState(TAXES_KEY.list())?.isInvalidated).toBe(true)
  })
})
