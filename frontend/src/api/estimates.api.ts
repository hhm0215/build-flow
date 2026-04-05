import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, PagedData, Estimate } from '../types'

export const ESTIMATES_KEY = {
  all: ['estimates'] as const,
  list: (params?: Record<string, string>) => ['estimates', 'list', params] as const,
  detail: (id: number) => ['estimates', 'detail', id] as const,
}

const fetchEstimates = async (params?: Record<string, string>) => {
  const res = await axiosInstance.get<ApiResponse<PagedData<Estimate>>>('/estimates', { params })
  return res.data.data
}

const createEstimate = async (body: Partial<Estimate>) => {
  const res = await axiosInstance.post<ApiResponse<Estimate>>('/estimates', body)
  return res.data.data
}

export function useEstimates(params?: Record<string, string>) {
  return useQuery({
    queryKey: ESTIMATES_KEY.list(params),
    queryFn: () => fetchEstimates(params),
  })
}

export function useCreateEstimate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createEstimate,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ESTIMATES_KEY.all }),
  })
}
