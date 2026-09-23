import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import axiosInstance from './axiosInstance'
import { PURCHASES_KEY, useCreatePurchase, useDeletePurchase, useUpdatePurchase } from './purchases.api'
import { SITES_KEY } from './sites.api'
import { DASHBOARD_KEY } from './dashboard.api'

afterEach(() => vi.restoreAllMocks())

function setup<T>(hook: () => T) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 300_000 } } })
  queryClient.setQueryData(PURCHASES_KEY.list(), [])
  queryClient.setQueryData(SITES_KEY.profit(3), { siteId: 3, totalPurchaseAmount: 0 })
  queryClient.setQueryData(DASHBOARD_KEY.stats, { totalPurchaseAmount: 0 })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, ...renderHook(hook, { wrapper }) }
}

function expectDependentsInvalidated(queryClient: QueryClient) {
  expect(queryClient.getQueryState(PURCHASES_KEY.list())?.isInvalidated).toBe(true)
  expect(queryClient.getQueryState(SITES_KEY.profit(3))?.isInvalidated).toBe(true)
  expect(queryClient.getQueryState(DASHBOARD_KEY.stats)?.isInvalidated).toBe(true)
}

describe('purchase mutations', () => {
  it('create 성공 후 매입·손익·대시보드 캐시를 stale 처리한다', async () => {
    vi.spyOn(axiosInstance, 'post').mockResolvedValue({ data: { data: { id: 7, siteId: 3 } } })
    const { result, queryClient } = setup(() => useCreatePurchase())

    await act(async () => {
      await result.current.mutateAsync({ siteId: 3, itemName: '자재', quantity: 1, unitPrice: 1000 })
    })

    expectDependentsInvalidated(queryClient)
  })

  it('update는 siteId 없는 정확한 PUT body를 보내고 관련 캐시를 stale 처리한다', async () => {
    vi.spyOn(axiosInstance, 'put').mockResolvedValue({ data: { data: { id: 7, siteId: 3 } } })
    const { result, queryClient } = setup(() => useUpdatePurchase())
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')

    await act(async () => {
      await result.current.mutateAsync({
        id: 7, itemName: '수정 자재', quantity: 2, unitPrice: 3000,
        supplier: '공급사', purchaseDate: '2026-09-24', memo: '메모',
      })
    })

    expect(axiosInstance.put).toHaveBeenCalledWith('/purchases/7', {
      itemName: '수정 자재', quantity: 2, unitPrice: 3000,
      supplier: '공급사', purchaseDate: '2026-09-24', memo: '메모',
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: SITES_KEY.profit(3), refetchType: 'none',
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: DASHBOARD_KEY.all, refetchType: 'none',
    })
    expectDependentsInvalidated(queryClient)
  })

  it('delete는 siteId를 URL body에 보내지 않고 캐시 키에만 사용한다', async () => {
    vi.spyOn(axiosInstance, 'delete').mockResolvedValue({})
    const { result, queryClient } = setup(() => useDeletePurchase())

    await act(async () => { await result.current.mutateAsync({ id: 7, siteId: 3 }) })

    expect(axiosInstance.delete).toHaveBeenCalledWith('/purchases/7')
    expectDependentsInvalidated(queryClient)
  })
})
