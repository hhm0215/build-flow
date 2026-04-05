// ─────────────────────────────────────────────
// API 함수: axios로 실제 요청을 보냄 (개발 시 MSW가 가로챔)
// Query 훅:  TanStack Query로 캐싱·로딩·에러 상태 관리
//
// 이 구조의 장점:
//   - API 함수는 "어떻게 요청하나"만 담당
//   - 훅은 "언제 fetch하고 어떻게 캐싱하나"만 담당
//   - 컴포넌트는 useXxx() 호출 하나로 데이터 사용
// ─────────────────────────────────────────────
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, PagedData, Site } from '../types'

// ── Query Key 상수 ─────────────────────────────
// 문자열 하드코딩 대신 상수로 관리 → 오타 방지, 무효화 시 일관성
export const SITES_KEY = {
  all: ['sites'] as const,
  list: (params?: Record<string, string>) => ['sites', 'list', params] as const,
  detail: (id: number) => ['sites', 'detail', id] as const,
}

// ── API 함수 ───────────────────────────────────
const fetchSites = async (params?: Record<string, string>) => {
  const res = await axiosInstance.get<ApiResponse<PagedData<Site>>>('/sites', { params })
  return res.data.data
}

const fetchSite = async (id: number) => {
  const res = await axiosInstance.get<ApiResponse<Site>>(`/sites/${id}`)
  return res.data.data
}

const createSite = async (body: Omit<Site, 'id' | 'totalRevenue' | 'totalCost' | 'margin' | 'marginRate'>) => {
  const res = await axiosInstance.post<ApiResponse<Site>>('/sites', body)
  return res.data.data
}

const deleteSite = async (id: number) => {
  await axiosInstance.delete(`/sites/${id}`)
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
    enabled: !!id, // id가 없으면 요청 안 함
  })
}

export function useCreateSite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createSite,
    // 생성 성공 시 목록 캐시를 무효화 → 자동으로 재조회
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
