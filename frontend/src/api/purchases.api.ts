import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, PagedData, Purchase } from '../types'

export const PURCHASES_KEY = {
  all: ['purchases'] as const,
  list: (params?: Record<string, string>) => ['purchases', 'list', params] as const,
}

const fetchPurchases = async (params?: Record<string, string>) => {
  const res = await axiosInstance.get<ApiResponse<PagedData<Purchase>>>('/purchases', { params })
  return res.data.data
}

const createPurchase = async (body: Partial<Purchase>) => {
  const res = await axiosInstance.post<ApiResponse<Purchase>>('/purchases', body)
  return res.data.data
}

export function usePurchases(params?: Record<string, string>) {
  return useQuery({
    queryKey: PURCHASES_KEY.list(params),
    queryFn: () => fetchPurchases(params),
  })
}

export function useCreatePurchase() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createPurchase,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PURCHASES_KEY.all }),
  })
}
