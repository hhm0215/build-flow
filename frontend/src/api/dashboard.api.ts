import { useQuery } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, DashboardStats, DashboardSummary } from '../types'

export const DASHBOARD_KEY = {
  stats: ['dashboard', 'stats'] as const,
  summary: ['dashboard', 'summary'] as const,
}

const fetchStats = async () => {
  const res = await axiosInstance.get<ApiResponse<DashboardStats>>('/dashboard/stats')
  return res.data.data
}

const fetchSummary = async () => {
  const res = await axiosInstance.get<ApiResponse<DashboardSummary>>('/dashboard/summary')
  return res.data.data
}

export function useDashboardStats() {
  return useQuery({
    queryKey: DASHBOARD_KEY.stats,
    queryFn: fetchStats,
  })
}

export function useDashboardSummary() {
  return useQuery({
    queryKey: DASHBOARD_KEY.summary,
    queryFn: fetchSummary,
    staleTime: 10 * 60 * 1000, // AI 요약은 10분간 캐시
  })
}
