import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import axiosInstance from './axiosInstance'
import { TAXES_KEY, useConfirmPayment, useDeleteTax, useUpdateTax } from './taxes.api'

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

describe('tax mutations', () => {
  it('update는 siteId 없는 정확한 PUT body를 보내고 tax prefix를 무효화한다', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 300_000 } } })
    queryClient.setQueryData(TAXES_KEY.list(), [])
    queryClient.setQueryData(TAXES_KEY.outstanding(3), { outstandingAmount: 0 })
    const put = vi.spyOn(axiosInstance, 'put').mockResolvedValue({ data: { data: { id: 7, siteId: 3 } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useUpdateTax(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({
        id: 7, type: 'SALES', supplyAmount: 100, taxAmount: 10,
        counterparty: '거래처', issueDate: '2026-09-24', memo: '메모',
      })
    })

    expect(put).toHaveBeenCalledWith('/taxes/7', {
      type: 'SALES', supplyAmount: 100, taxAmount: 10,
      counterparty: '거래처', issueDate: '2026-09-24', memo: '메모',
    })
    expect(queryClient.getQueryState(TAXES_KEY.list())?.isInvalidated).toBe(true)
    expect(queryClient.getQueryState(TAXES_KEY.outstanding(3))?.isInvalidated).toBe(true)
  })

  it('delete는 siteId를 URL이나 body에 보내지 않고 tax prefix를 무효화한다', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 300_000 } } })
    queryClient.setQueryData(TAXES_KEY.list(), [])
    queryClient.setQueryData(TAXES_KEY.outstanding(3), { outstandingAmount: 0 })
    const remove = vi.spyOn(axiosInstance, 'delete').mockResolvedValue({})
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useDeleteTax(), { wrapper })

    await act(async () => { await result.current.mutateAsync({ id: 7, siteId: 3 }) })

    expect(remove).toHaveBeenCalledWith('/taxes/7')
    expect(queryClient.getQueryState(TAXES_KEY.list())?.isInvalidated).toBe(true)
    expect(queryClient.getQueryState(TAXES_KEY.outstanding(3))?.isInvalidated).toBe(true)
  })
})
