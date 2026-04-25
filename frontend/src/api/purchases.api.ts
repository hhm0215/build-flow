import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, Purchase, PurchaseCreateRequest } from '../types'

export const PURCHASES_KEY = {
  all: ['purchases'] as const,
  list: (params?: Record<string, string>) => ['purchases', 'list', params] as const,
  detail: (id: number) => ['purchases', 'detail', id] as const,
}

const fetchPurchases = async (params?: Record<string, string>) => {
  const res = await axiosInstance.get<ApiResponse<Purchase[]>>('/purchases', { params })
  return res.data.data
}

const createPurchase = async (body: PurchaseCreateRequest) => {
  const res = await axiosInstance.post<ApiResponse<Purchase>>('/purchases', body)
  return res.data.data
}

const updatePurchase = async ({ id, ...body }: PurchaseCreateRequest & { id: number }) => {
  const res = await axiosInstance.put<ApiResponse<Purchase>>(`/purchases/${id}`, body)
  return res.data.data
}

const deletePurchase = async (id: number) => {
  await axiosInstance.delete(`/purchases/${id}`)
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

export function useUpdatePurchase() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: updatePurchase,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PURCHASES_KEY.all }),
  })
}

export function useDeletePurchase() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deletePurchase,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PURCHASES_KEY.all }),
  })
}
