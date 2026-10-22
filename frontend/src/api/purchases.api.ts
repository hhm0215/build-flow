import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import {
  ApiResponse, Purchase, PurchaseCreateRequest, PurchaseEvidenceSummary, PurchaseUpdateRequest,
} from '../types'
import { SITES_KEY } from './sites.api'
import { DASHBOARD_KEY } from './dashboard.api'

export const PURCHASES_KEY = {
  all: ['purchases'] as const,
  list: (params?: Record<string, string>) => ['purchases', 'list', params] as const,
  detail: (id: number) => ['purchases', 'detail', id] as const,
  /** 세금계산서 변경 시에도 무효화한다(taxes.api). */
  evidenceSummaryAll: ['purchases', 'evidence-summary'] as const,
  evidenceSummary: (siteId: number) => ['purchases', 'evidence-summary', siteId] as const,
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

interface EvidenceVariables {
  id: number
  taxInvoiceId: number
}

const linkPurchaseEvidence = async ({ id, taxInvoiceId }: EvidenceVariables) => {
  const res = await axiosInstance.put<ApiResponse<Purchase>>(`/purchases/${id}/evidence`, { taxInvoiceId })
  return res.data.data
}

const unlinkPurchaseEvidence = async (id: number) => {
  const res = await axiosInstance.delete<ApiResponse<Purchase>>(`/purchases/${id}/evidence`)
  return res.data.data
}

const fetchEvidenceSummary = async (siteId: number) => {
  const res = await axiosInstance.get<ApiResponse<PurchaseEvidenceSummary>>(
    '/purchases/evidence-summary', { params: { siteId: String(siteId) } })
  return res.data.data
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

/** 증빙 연결·해제는 원가(손익)에 영향이 없어 매입 목록·대조 요약만 갱신한다. */
export function useLinkPurchaseEvidence() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: linkPurchaseEvidence,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PURCHASES_KEY.all }),
  })
}

export function useUnlinkPurchaseEvidence() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: unlinkPurchaseEvidence,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PURCHASES_KEY.all }),
  })
}

export function usePurchaseEvidenceSummary(siteId: number | undefined) {
  return useQuery({
    queryKey: PURCHASES_KEY.evidenceSummary(siteId ?? 0),
    queryFn: () => fetchEvidenceSummary(siteId!),
    enabled: siteId != null && siteId > 0,
  })
}
