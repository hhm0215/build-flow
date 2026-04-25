import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, TaxInvoice, TaxInvoiceCreateRequest } from '../types'

export const TAXES_KEY = {
  all: ['taxes'] as const,
  list: (params?: Record<string, string>) => ['taxes', 'list', params] as const,
  outstanding: (siteId?: number) => ['taxes', 'outstanding', siteId] as const,
}

const fetchTaxes = async (params?: Record<string, string>) => {
  const res = await axiosInstance.get<ApiResponse<TaxInvoice[]>>('/taxes', { params })
  return res.data.data
}

const createTax = async (body: TaxInvoiceCreateRequest) => {
  const res = await axiosInstance.post<ApiResponse<TaxInvoice>>('/taxes', body)
  return res.data.data
}

const updateTax = async ({ id, ...body }: TaxInvoiceCreateRequest & { id: number }) => {
  const res = await axiosInstance.put<ApiResponse<TaxInvoice>>(`/taxes/${id}`, body)
  return res.data.data
}

const deleteTax = async (id: number) => {
  await axiosInstance.delete(`/taxes/${id}`)
}

const confirmPayment = async (id: number) => {
  const res = await axiosInstance.patch<ApiResponse<TaxInvoice>>(`/taxes/${id}/confirm-payment`)
  return res.data.data
}

const fetchOutstanding = async (siteId?: number) => {
  const params = siteId ? { siteId: String(siteId) } : undefined
  const res = await axiosInstance.get<ApiResponse<{ outstandingAmount: number }>>('/taxes/outstanding', { params })
  return res.data.data
}

export function useTaxes(params?: Record<string, string>) {
  return useQuery({
    queryKey: TAXES_KEY.list(params),
    queryFn: () => fetchTaxes(params),
  })
}

export function useCreateTax() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createTax,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TAXES_KEY.all }),
  })
}

export function useUpdateTax() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: updateTax,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TAXES_KEY.all }),
  })
}

export function useDeleteTax() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteTax,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TAXES_KEY.all }),
  })
}

export function useConfirmPayment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: confirmPayment,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TAXES_KEY.all }),
  })
}

export function useOutstanding(siteId?: number) {
  return useQuery({
    queryKey: TAXES_KEY.outstanding(siteId),
    queryFn: () => fetchOutstanding(siteId),
  })
}
