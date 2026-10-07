import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import axiosInstance from './axiosInstance'
import { CLIENTS_KEY, useUpdateClient } from './clients.api'
import { SITES_KEY } from './sites.api'

afterEach(() => vi.restoreAllMocks())

describe('client mutations', () => {
  it('수정 후 거래처와 연결 현장 캐시를 stale 처리한다', async () => {
    const put = vi.spyOn(axiosInstance, 'put').mockResolvedValue({ data: { data: { id: 6 } } })
    const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 300_000 } } })
    queryClient.setQueryData(CLIENTS_KEY, [])
    queryClient.setQueryData(SITES_KEY.list(), [])
    queryClient.setQueryData(SITES_KEY.detail(17), { id: 17 })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useUpdateClient(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({
        id: 6, companyName: '수정된 거래처', representative: null, businessNo: null,
        phone: null, email: null, address: null, memo: null,
      })
    })

    expect(put).toHaveBeenCalledWith('/clients/6', {
      companyName: '수정된 거래처', representative: null, businessNo: null,
      phone: null, email: null, address: null, memo: null,
    })
    expect(queryClient.getQueryState(CLIENTS_KEY)?.isInvalidated).toBe(true)
    expect(queryClient.getQueryState(SITES_KEY.list())?.isInvalidated).toBe(true)
    expect(queryClient.getQueryState(SITES_KEY.detail(17))?.isInvalidated).toBe(true)
  })
})
