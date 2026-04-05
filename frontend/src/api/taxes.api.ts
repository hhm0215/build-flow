import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, PagedData, TaxInvoice } from '../types'

export const TAXES_KEY = {
  all: ['taxes'] as const,
  list: (params?: Record<string, string>) => ['taxes', 'list', params] as const,
}

const fetchTaxes = async (params?: Record<string, string>) => {
  const res = await axiosInstance.get<ApiResponse<PagedData<TaxInvoice>>>('/taxes', { params })
  return res.data.data
}

const markAsPaid = async (id: number) => {
  const res = await axiosInstance.patch<ApiResponse<TaxInvoice>>(`/taxes/${id}/paid`)
  return res.data.data
}

export function useTaxes(params?: Record<string, string>) {
  return useQuery({
    queryKey: TAXES_KEY.list(params),
    queryFn: () => fetchTaxes(params),
  })
}

export function useMarkAsPaid() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: markAsPaid,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TAXES_KEY.all }),
  })
}
