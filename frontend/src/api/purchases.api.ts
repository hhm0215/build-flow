import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, Purchase, PurchaseCreateRequest, PurchaseUpdateRequest } from '../types'
import { SITES_KEY } from './sites.api'
import { DASHBOARD_KEY } from './dashboard.api'

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

const updatePurchase = async ({ id, ...body }: PurchaseUpdateRequest & { id: number }) => {
  const res = await axiosInstance.put<ApiResponse<Purchase>>(`/purchases/${id}`, body)
  return res.data.data
}

interface DeletePurchaseVariables {
  id: number
  siteId: number
}

const deletePurchase = async ({ id }: DeletePurchaseVariables) => {
  await axiosInstance.delete(`/purchases/${id}`)
}

async function invalidatePurchaseDependents(queryClient: QueryClient, siteId: number) {
  await queryClient.invalidateQueries({ queryKey: PURCHASES_KEY.all })
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: SITES_KEY.profit(siteId), refetchType: 'none' }),
    queryClient.invalidateQueries({ queryKey: DASHBOARD_KEY.all, refetchType: 'none' }),
  ])
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
    onSuccess: (purchase) => invalidatePurchaseDependents(queryClient, purchase.siteId),
  })
}

export function useUpdatePurchase() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: updatePurchase,
    onSuccess: (purchase) => invalidatePurchaseDependents(queryClient, purchase.siteId),
  })
}

export function useDeletePurchase() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deletePurchase,
    onSuccess: (_data, variables) => invalidatePurchaseDependents(queryClient, variables.siteId),
  })
}
