import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import {
  ApiResponse, PaymentConfirmRequest, TaxInvoice, TaxInvoiceCreateRequest, TaxInvoiceUpdateRequest,
  TaxPayment, TaxPaymentCancelRequest, TaxPaymentCreateRequest,
} from '../types'
import { PURCHASES_KEY } from './purchases.api'

export const TAXES_KEY = {
  all: ['taxes'] as const,
  list: (params?: Record<string, string>) => ['taxes', 'list', params] as const,
  outstanding: (siteId?: number) => ['taxes', 'outstanding', siteId] as const,
  payments: (taxId: number) => ['taxes', 'payments', taxId] as const,
}

const fetchTaxes = async (params?: Record<string, string>) => {
  const res = await axiosInstance.get<ApiResponse<TaxInvoice[]>>('/taxes', { params })
  return res.data.data
}

const createTax = async (body: TaxInvoiceCreateRequest) => {
  const res = await axiosInstance.post<ApiResponse<TaxInvoice>>('/taxes', body)
  return res.data.data
}

const updateTax = async ({ id, ...body }: TaxInvoiceUpdateRequest & { id: number }) => {
  const res = await axiosInstance.put<ApiResponse<TaxInvoice>>(`/taxes/${id}`, body)
  return res.data.data
}

interface DeleteTaxVariables {
  id: number
  siteId: number
}

const deleteTax = async ({ id }: DeleteTaxVariables) => {
  await axiosInstance.delete(`/taxes/${id}`)
}

const confirmPayment = async ({ id, paymentDate }: PaymentConfirmRequest) => {
  const res = await axiosInstance.patch<ApiResponse<TaxInvoice>>(`/taxes/${id}/confirm-payment`, { paymentDate })
  return res.data.data
}

const fetchPayments = async (taxId: number) => {
  const res = await axiosInstance.get<ApiResponse<TaxPayment[]>>(`/taxes/${taxId}/payments`)
  return res.data.data
}

const recordPayment = async ({ taxId, ...body }: TaxPaymentCreateRequest & { taxId: number }) => {
  const res = await axiosInstance.post<ApiResponse<TaxPayment>>(`/taxes/${taxId}/payments`, body)
  return res.data.data
}

interface CancelPaymentVariables extends TaxPaymentCancelRequest {
  taxId: number
  paymentId: number
}

const cancelPayment = async ({ taxId, paymentId, reason }: CancelPaymentVariables) => {
  const res = await axiosInstance.patch<ApiResponse<TaxPayment>>(
    `/taxes/${taxId}/payments/${paymentId}/cancel`, { reason: reason?.trim() || undefined })
  return res.data.data
}

const fetchOutstanding = async (siteId?: number) => {
  const params = siteId ? { siteId: String(siteId) } : undefined
  const res = await axiosInstance.get<ApiResponse<{ outstandingAmount: number }>>('/taxes/outstanding', { params })
  return res.data.data
}

/** 매입 계산서 생성·수정·삭제는 매입 증빙 대조 요약도 바꾼다. */
async function invalidateTaxAndEvidence(queryClient: ReturnType<typeof useQueryClient>) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: TAXES_KEY.all }),
    queryClient.invalidateQueries({ queryKey: PURCHASES_KEY.evidenceSummaryAll }),
  ])
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
    onSuccess: () => invalidateTaxAndEvidence(queryClient),
  })
}

export function useUpdateTax() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: updateTax,
    onSuccess: () => invalidateTaxAndEvidence(queryClient),
  })
}

export function useDeleteTax() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteTax,
    onSuccess: () => invalidateTaxAndEvidence(queryClient),
  })
}

export function useConfirmPayment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: confirmPayment,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TAXES_KEY.all }),
  })
}

export function useTaxPayments(taxId: number) {
  return useQuery({
    queryKey: TAXES_KEY.payments(taxId),
    queryFn: () => fetchPayments(taxId),
  })
}

/** 입금 기록·취소는 목록 잔액·미수금·입금 내역을 함께 갱신한다(모두 'taxes' 키 하위). */
export function useRecordTaxPayment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: recordPayment,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TAXES_KEY.all }),
  })
}

export function useCancelTaxPayment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: cancelPayment,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TAXES_KEY.all }),
  })
}

export function useOutstanding(siteId?: number) {
  return useQuery({
    queryKey: TAXES_KEY.outstanding(siteId),
    queryFn: () => fetchOutstanding(siteId),
  })
}
