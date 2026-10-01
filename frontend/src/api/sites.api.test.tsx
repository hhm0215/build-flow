import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import axiosInstance from './axiosInstance'
import { useCreateSite, useDeleteSite, useUpdateSite, useUpdateSiteStatus, SITES_KEY } from './sites.api'
import { DASHBOARD_KEY } from './dashboard.api'

afterEach(() => vi.restoreAllMocks())

function setup<T>(hook: () => T) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 300_000 } } })
  queryClient.setQueryData(SITES_KEY.list(), [])
  queryClient.setQueryData(DASHBOARD_KEY.stats, { activeSiteCount: 0 })
  queryClient.setQueryData(DASHBOARD_KEY.summary, { summary: '' })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, ...renderHook(hook, { wrapper }) }
}

function expectSiteAndDashboardInvalidated(queryClient: QueryClient) {
  expect(queryClient.getQueryState(SITES_KEY.list())?.isInvalidated).toBe(true)
  expect(queryClient.getQueryState(DASHBOARD_KEY.stats)?.isInvalidated).toBe(true)
  expect(queryClient.getQueryState(DASHBOARD_KEY.summary)?.isInvalidated).toBe(true)
}

describe('site mutations', () => {
  it('생성 후 현장 목록과 대시보드 캐시를 stale 처리한다', async () => {
    vi.spyOn(axiosInstance, 'post').mockResolvedValue({ data: { data: { id: 1 } } })
    const { result, queryClient } = setup(() => useCreateSite())

    await act(async () => { await result.current.mutateAsync({ siteName: '[시현] 현장' }) })

    expectSiteAndDashboardInvalidated(queryClient)
  })

  it('수정 후 현장 목록과 대시보드 캐시를 stale 처리한다', async () => {
    vi.spyOn(axiosInstance, 'put').mockResolvedValue({ data: { data: { id: 1 } } })
    const { result, queryClient } = setup(() => useUpdateSite())

    await act(async () => {
      await result.current.mutateAsync({
        id: 1, siteName: '[시현] 수정 현장', clientId: null, address: null,
        startDate: null, endDate: null, memo: null,
      })
    })

    expectSiteAndDashboardInvalidated(queryClient)
  })

  it('상태 변경 후 현장 목록과 대시보드 캐시를 stale 처리한다', async () => {
    vi.spyOn(axiosInstance, 'patch').mockResolvedValue({ data: { data: { id: 1 } } })
    const { result, queryClient } = setup(() => useUpdateSiteStatus())

    await act(async () => { await result.current.mutateAsync({ id: 1, status: 'COMPLETED' }) })

    expectSiteAndDashboardInvalidated(queryClient)
  })

  it('삭제 후 현장 목록과 대시보드 캐시를 stale 처리한다', async () => {
    vi.spyOn(axiosInstance, 'delete').mockResolvedValue({})
    const { result, queryClient } = setup(() => useDeleteSite())

    await act(async () => { await result.current.mutateAsync(1) })

    expectSiteAndDashboardInvalidated(queryClient)
  })
})
