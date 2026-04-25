import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, Site, SiteCreateRequest, Profit } from '../types'

export const SITES_KEY = {
  all: ['sites'] as const,
  list: (params?: Record<string, string>) => ['sites', 'list', params] as const,
  detail: (id: number) => ['sites', 'detail', id] as const,
  profit: (id: number) => ['sites', 'profit', id] as const,
}

// ── API 함수 ───────────────────────────────────
const fetchSites = async (params?: Record<string, string>) => {
  const res = await axiosInstance.get<ApiResponse<Site[]>>('/sites', { params })
  return res.data.data
}

const fetchSite = async (id: number) => {
  const res = await axiosInstance.get<ApiResponse<Site>>(`/sites/${id}`)
  return res.data.data
}

const createSite = async (body: SiteCreateRequest) => {
  const res = await axiosInstance.post<ApiResponse<Site>>('/sites', body)
  return res.data.data
}

const updateSite = async ({ id, ...body }: SiteCreateRequest & { id: number }) => {
  const res = await axiosInstance.put<ApiResponse<Site>>(`/sites/${id}`, body)
  return res.data.data
}

const deleteSite = async (id: number) => {
  await axiosInstance.delete(`/sites/${id}`)
}

const updateSiteStatus = async ({ id, status }: { id: number; status: string }) => {
  const res = await axiosInstance.patch<ApiResponse<Site>>(`/sites/${id}/status`, { status })
  return res.data.data
}

const fetchProfit = async (siteId: number) => {
  const res = await axiosInstance.get<ApiResponse<Profit>>(`/sites/${siteId}/profit`)
  return res.data.data
}

// ── TanStack Query 훅 ──────────────────────────
export function useSites(params?: Record<string, string>) {
  return useQuery({
    queryKey: SITES_KEY.list(params),
    queryFn: () => fetchSites(params),
  })
}

export function useSite(id: number) {
  return useQuery({
    queryKey: SITES_KEY.detail(id),
    queryFn: () => fetchSite(id),
    enabled: !!id,
  })
}

export function useCreateSite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createSite,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: SITES_KEY.all }),
  })
}

export function useUpdateSite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: updateSite,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: SITES_KEY.all }),
  })
}

export function useDeleteSite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteSite,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: SITES_KEY.all }),
  })
}

export function useUpdateSiteStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: updateSiteStatus,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: SITES_KEY.all }),
  })
}

export function useSiteProfit(siteId: number) {
  return useQuery({
    queryKey: SITES_KEY.profit(siteId),
    queryFn: () => fetchProfit(siteId),
    enabled: !!siteId,
  })
}
